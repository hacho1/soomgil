package backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TourSyncTests {
    Map<String, String> place(String id, String name, String flag, String modified) {
        return Map.of("contentid", id, "title", name, "addr1", "강원특별자치도 강릉시", "showflag", flag, "modifiedtime", modified, "mapx", "128.9", "mapy", "37.8");
    }
    class Provider implements AutoCloseable {
        final CatalogFixture db = new CatalogFixture();
        final AtomicReference<String> reply = new AtomicReference<>(), request = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server;
        Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/areaBasedSyncList2", exchange -> {
                calls.incrementAndGet(); request.set(exchange.getRequestURI().getQuery());
                byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
        }
        void response(long total, List<Map<String, String>> rows) throws Exception {
            reply.set(db.mapper.writeValueAsString(Map.of("response", Map.of("header", Map.of("resultCode", "0000"), "body", Map.of("totalCount", total, "items", Map.of("item", rows))))));
        }
        TourCatalog catalog(int limit) { return db.catalog("http://127.0.0.1:" + server.getAddress().getPort(), limit); }
        TourSyncService sync(TourCatalog catalog) {
            var sync = new TourSyncService(db.jdbc, catalog, db.queries, true); sync.initialize();
            db.jdbc.update("UPDATE tour_sync_state SET page_size=2 WHERE id=1"); return sync;
        }
        public void close() { server.stop(0); }
    }
    @Test void restartContinuesCheckpointAndCompletionSchedulesNextRefresh() throws Exception {
        try (var p = new Provider()) {
            p.response(3, List.of(place("1", "첫 해변", "1", "20260914090000"), place("2", "두번째 숲길", "1", "20260914090000")));
            var sync = p.sync(p.catalog(10)); sync.runOnePage();
            assertThat(sync.status()).containsEntry("processed", 2L).containsEntry("initialComplete", false);
            assertThat(p.request.get()).contains("showflag=1", "pageNo=1");
            var restored = p.sync(p.catalog(10)); restored.control("resume");
            p.response(3, List.of(place("3", "세번째 카페", "1", "20260914090000")));
            var response = p.db.mapper.readTree(p.reply.get());
            ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("response").path("body")).put("pageNo", 2).put("numOfRows", 1);
            p.reply.set(p.db.mapper.writeValueAsString(response)); restored.runOnePage();
            assertThat(restored.status()).containsEntry("processed", 3L).containsEntry("initialComplete", true).containsEntry("state", "waiting");
            assertThat(p.request.get()).contains("pageNo=2");
            assertThat(p.db.queries.publicCount()).isEqualTo(3);
            assertThat(((Number) restored.status().get("nextRunAt")).longValue()).isGreaterThan(System.currentTimeMillis());
        }
    }
    @Test void invalidPageNeverAdvancesOrClaimsCompletion() throws Exception {
        try (var p = new Provider()) {
            p.response(4, List.of(place("1", "잘린 응답", "1", "20260914090000")));
            var sync = p.sync(p.catalog(10)); sync.runOnePage();
            assertThat(sync.status()).containsEntry("page", 1).containsEntry("processed", 0L).containsEntry("initialComplete", false);
            assertThat(p.db.queries.publicCount()).isZero();
        }
    }
    @Test void failedCheckpointRollsBackPlacesAndBudgetSurvivesRestart() throws Exception {
        try (var p = new Provider()) {
            p.response(1, List.of(place("1", "해변", "1", "20260914090000")));
            assertThatThrownBy(() -> p.catalog(1).syncPage(1, 2, "", batch -> { throw new IllegalStateException("test rollback"); })).isInstanceOf(IllegalStateException.class);
            assertThat(p.db.queries.publicCount()).isZero();
            assertThat(p.catalog(1).syncPage(1, 2, "", batch -> {})).isEqualTo("budget-limited");
            assertThat(p.calls.get()).isEqualTo(1);
        }
    }
    @Test void syncKeepsPlaceIdentityModerationAndFavoriteLinksAcrossProviderChanges() throws Exception {
        try (var p = new Provider()) {
            var catalog = p.catalog(10);
            p.response(1, List.of(place("10", "원래 이름", "1", "20260913090000"))); catalog.syncPage(1, 2, "", batch -> {});
            long id = p.db.jdbc.queryForObject("SELECT id FROM places WHERE tour_content_id='10'", Long.class);
            p.db.jdbc.execute("CREATE TABLE favorites(place_id BIGINT)"); p.db.jdbc.update("INSERT INTO favorites VALUES(?)", id);
            p.db.jdbc.update("UPDATE places SET approved=FALSE WHERE id=?", id);
            var migrated = new java.util.HashMap<>(place("11", "바뀐 이름", "1", "20260914090000")); migrated.put("oldContentid", "10");
            p.response(1, List.of(migrated)); catalog.syncPage(1, 2, "20260912", batch -> {});
            assertThat(p.db.jdbc.queryForObject("SELECT id FROM places WHERE tour_content_id='11'", Long.class)).isEqualTo(id);
            assertThat(p.db.jdbc.queryForObject("SELECT approved FROM places WHERE id=?", Boolean.class, id)).isFalse();
            assertThat(p.db.jdbc.queryForObject("SELECT place_id FROM favorites", Long.class)).isEqualTo(id);
            p.response(1, List.of(place("11", "바뀐 이름", "0", "20260914100000"))); catalog.syncPage(1, 2, "20260912", batch -> {});
            assertThat(p.db.jdbc.queryForObject("SELECT tour_visible FROM places WHERE id=?", Boolean.class, id)).isFalse();
            p.response(1, List.of(place("11", "늦게 도착한 과거 데이터", "1", "20260914090000"))); catalog.syncPage(1, 2, "", batch -> {});
            assertThat(p.db.jdbc.queryForObject("SELECT tour_visible FROM places WHERE id=?", Boolean.class, id)).isFalse();
            assertThat(p.db.jdbc.queryForObject("SELECT COUNT(*) FROM places", Integer.class)).isEqualTo(1);
        }
    }
    @Test void incrementalRefreshWalksEveryDateIncludingEmptyDaysAndCanResume() throws Exception {
        try (var p = new Provider()) {
            var sync = p.sync(p.catalog(20));
            long now = System.currentTimeMillis();
            var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
            p.db.jdbc.update("UPDATE tour_sync_state SET mode='idle',last_completed_at=?,last_started_at=?,full_completed_at=?,catalog_version=? WHERE id=1", now, now, now, PlaceContent.CATALOG_VERSION);
            for (int days = 2; days >= 0; days--) {
                String date = today.minusDays(days).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                p.response(days == 1 ? 0 : 1, days == 1 ? List.of() : List.of(place("" + (days + 1), "갱신 " + days, "1", date + "010000")));
                sync.control("resume"); sync.runOnePage();
                assertThat(p.request.get()).contains("modifiedtime=" + date).doesNotContain("showflag");
                if (days > 0) { assertThat(sync.status()).containsEntry("mode", "delta"); sync = p.sync(p.catalog(20)); }
            }
            assertThat(sync.status()).containsEntry("mode", "idle").containsEntry("processed", 2L);
            assertThat(p.db.queries.publicCount()).isEqualTo(2);
        }
    }
    @Test void weeklyFullRefreshCatchesUpMissedDatesBeforeAdvancingWatermark() throws Exception {
        try (var p = new Provider()) {
            var sync = p.sync(p.catalog(30));
            long now = System.currentTimeMillis();
            long yesterday = now - 86400000L;
            p.db.jdbc.update("UPDATE tour_sync_state SET mode='idle',last_completed_at=?,last_started_at=?,full_completed_at=? WHERE id=1", yesterday, yesterday, now - 8L * 86400000);
            p.response(0, List.of());
            for (int day = 0; day < 4; day++) {
                sync.control("resume"); sync.runOnePage();
                assertThat(p.request.get()).contains("modifiedtime=").doesNotContain("showflag");
            }
            assertThat(sync.status()).containsEntry("mode", "idle");
            assertThat(((Number) sync.status().get("nextRunAt")).longValue()).isLessThan(now + 60000);
            sync.control("resume"); sync.runOnePage();
            assertThat(p.request.get()).contains("showflag=1").doesNotContain("modifiedtime");
        }
    }
    @Test void pauseAndUnexpiredLeaseDoNotIssueNetworkRequests() throws Exception {
        try (var p = new Provider()) {
            var sync = p.sync(p.catalog(10)); sync.control("pause"); sync.runOnePage();
            assertThat(p.calls.get()).isZero();
            sync.control("resume"); p.db.jdbc.update("UPDATE tour_sync_state SET lease_until=? WHERE id=1", System.currentTimeMillis() + 60000);
            sync.runOnePage(); assertThat(p.calls.get()).isZero();
        }
    }
    @Test void legacyCatalogRefreshesTypesOnceAndPausedMigrationNeverRuns() throws Exception {
        try (var p = new Provider()) {
            var sync = p.sync(p.catalog(20));
            long now = System.currentTimeMillis();
            p.db.jdbc.update("UPDATE tour_sync_state SET mode='idle',last_completed_at=?,full_completed_at=?,paused=TRUE WHERE id=1",now,now);
            var restarted = p.sync(p.catalog(20)); restarted.runOnePage(); assertThat(p.calls.get()).isZero();
            var course = new java.util.HashMap<>(place("25","카페 숲길 여행 코스","1","20260915000000")); course.put("contenttypeid","25");
            p.response(1,List.of(course)); restarted.control("resume"); restarted.runOnePage();
            assertThat(p.request.get()).contains("showflag=1");
            assertThat(restarted.status()).containsEntry("contentTypesRefreshed",true);
            assertThat(p.db.jdbc.queryForMap("SELECT category,category_label,tour_content_type FROM places WHERE tour_content_id='25'"))
                    .containsEntry("category","course").containsEntry("category_label","여행 코스").containsEntry("tour_content_type","25");
            p.response(0,List.of()); restarted.control("run"); restarted.runOnePage();
            assertThat(p.request.get()).contains("modifiedtime=").doesNotContain("showflag");
        }
    }
    @Test void partlyCompletedLegacyFullPassCannotClaimNewMetadataVersion() throws Exception {
        try (var p = new Provider()) {
            var sync = p.sync(p.catalog(20));
            p.db.jdbc.update("UPDATE tour_sync_state SET mode='full',next_page=2,processed=2,run_started_at=?,run_catalog_version=0 WHERE id=1",System.currentTimeMillis());
            p.response(3,List.of(place("3","마지막 장소","1","20260915000000"))); sync.runOnePage();
            assertThat(sync.status()).containsEntry("contentTypesRefreshed",false);
            p.response(1,List.of(place("1","첫 장소","1","20260915000000"))); sync.control("run"); sync.runOnePage();
            assertThat(p.request.get()).contains("pageNo=1","showflag=1");
            assertThat(sync.status()).containsEntry("contentTypesRefreshed",true);
        }
    }
    @Test void databaseSearchPaginatesSynonymsAndUsesExactRadiusWithoutHiddenRecords() {
        var db = new CatalogFixture();
        for (int i = 0; i < 130; i++) db.jdbc.update("INSERT INTO places(name,address,category,category_label,region,tags,approved,mapx,mapy) VALUES(?,?,?,?,?,?,TRUE,?,?)",
                "해수욕장 " + i, "강원도 강릉시", "tour", "관광지", "강원", "해변", "128.9", "37.8");
        db.queries.backfill();
        var first = db.queries.search("바다", "강원", "tour", "name", 1, 6);
        var second = db.queries.search("바다", "강원", "tour", "name", 2, 6);
        assertThat(first.total()).isEqualTo(130); assertThat(first.hasMore()).isTrue(); assertThat(first.rows()).hasSize(6);
        assertThat(second.rows()).extracting(row -> row.get("id")).doesNotContainAnyElementsOf(first.rows().stream().map(row -> row.get("id")).toList());
        assertThat(db.queries.search("%", "", "", "name", 1, 6).total()).isZero();
        db.jdbc.update("UPDATE places SET tour_visible=FALSE WHERE id=1");
        assertThat(db.queries.nearby(128.9, 37.8, 1000, "", "near", 1, 6).total()).isEqualTo(129);
        assertThat(db.queries.nearby(127, 37, 1000, "", "near", 1, 6).total()).isZero();
    }
}
