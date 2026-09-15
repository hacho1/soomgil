package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TourDetailsTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> PLACE = Map.of("id", 1, "source", "tour-api:127291", "name", "동막해변", "address", "인천 강화", "mapx", "126.45", "mapy", "37.59");

    class Provider implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        volatile boolean fail, wrongId, emptyAccess, ambiguous, course;
        volatile long delay;
        final JdbcTemplate jdbc;

        Provider() throws Exception {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:details-" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            server.setExecutor(executor);
            server.createContext("/", this::reply);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        TourDetails service(int timeout, int budget) {
            var service = new TourDetails(jdbc, MAPPER, "server-only-key", url(), url(), timeout, budget);
            service.initialize(); return service;
        }
        TourDetails service() { return service(1500, 100); }

        void reply(HttpExchange exchange) {
            calls.incrementAndGet();
            try {
                if (delay > 0) Thread.sleep(delay);
                if (fail) { exchange.sendResponseHeaders(503, -1); return; }
                String operation = exchange.getRequestURI().getPath();
                Object item = switch (operation) {
                    case "/detailCommon2" -> Map.of("contentid", "127291", "contenttypeid", course ? "25" : "12", "overview", "바다 &amp; 산책<br>아름다운 곳<script>steal()</script>", "homepage", "<a href='https://example.com/place'>홈페이지</a>", "modifiedtime", "20260912121212");
                    case "/detailIntro2" -> course ? Map.of("contentid","127291","contenttypeid","25","distance","4km","taketime","2시간") : Map.of("contentid", "127291", "contenttypeid", "12", "usetime", "상시 개방", "restdate", "연중무휴", "parking", "가능", "infocenter", "032-123-4567");
                    case "/detailPetTour2" -> Map.of("contentid", wrongId ? "999" : "127291", "acmpyTypeCd", "야외만 동반 가능", "acmpyNeedMtr", "목줄 착용", "etcAcmpyInfo", "입마개 필수");
                    case "/detailWithTour2" -> emptyAccess ? "" : Map.of("contentid", "127291", "parking", "장애인 주차구역 없음", "route", "경사로 있음");
                    case "/searchKeyword2" -> {
                        var candidate = Map.of("contentid", "127291", "title", "동막해변", "addr1", "인천 강화", "mapx", "126.45", "mapy", "37.59");
                        yield ambiguous ? List.of(candidate, candidate) : List.of(candidate);
                    }
                    default -> "";
                };
                var body = Map.of("response", Map.of("header", Map.of("resultCode", "0000"), "body", Map.of("items", Map.of("item", item))));
                byte[] bytes = MAPPER.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    @Test
    void officialDetailsAreMappedSanitizedAndPersistedAcrossServiceRestarts() throws Exception {
        try (var provider = new Provider()) {
            var result = provider.service().get(PLACE);
            assertThat(result.get("state")).isEqualTo("ready");
            assertThat(result.get("overview").toString()).contains("바다 & 산책", "아름다운 곳").doesNotContain("script", "steal()");
            assertThat(result.get("facilities").toString()).contains("상시 개방", "연중무휴", "032-123-4567");
            assertThat(result.get("pet").toString()).contains("야외만 동반 가능", "목줄 착용");
            assertThat(result.get("accessibility").toString()).contains("장애인 주차구역 없음", "경사로 있음");
            assertThat(result.get("homepage")).isEqualTo("https://example.com/place");
            assertThat(result.toString()).doesNotContain("server-only-key");
            assertThat(provider.calls.get()).isEqualTo(4);
            assertThat(provider.service().get(PLACE).get("state")).isEqualTo("ready");
            assertThat(provider.calls.get()).isEqualTo(4);
            assertThat(provider.jdbc.queryForObject("SELECT COUNT(*) FROM tour_detail_cache", Integer.class)).isEqualTo(4);
        }
    }
    @Test void officialCourseReadsItsOverviewAndDurationWithoutClaimingFacilityWideAccess() throws Exception {
        try (var provider = new Provider()) {
            provider.course = true;
            var course = new java.util.HashMap<String,Object>(PLACE); course.put("contentKind","course");
            var result = provider.service().get(course);
            assertThat(provider.calls.get()).isEqualTo(2);
            assertThat(result.get("state")).isEqualTo("ready");
            assertThat(result.get("facilities").toString()).contains("4km","2시간");
            assertThat(result.get("pet")).isEqualTo(List.of());
            assertThat(result.get("accessibility")).isEqualTo(List.of());
        }
    }

    @Test
    void missingAccessibilityIsNotDescribedAsAccessibleOrAsAnOutage() throws Exception {
        try (var provider = new Provider()) {
            provider.emptyAccess = true;
            var result = provider.service().get(PLACE);
            assertThat(result.get("accessibility")).isEqualTo(List.of());
            assertThat(result.get("state")).isEqualTo("ready");
            assertThat(result.get("sources").toString()).contains("state=empty");
        }
    }

    @Test
    void mismatchedProviderIdIsRejectedWithoutLosingOtherSources() throws Exception {
        try (var provider = new Provider()) {
            provider.wrongId = true;
            var result = provider.service().get(PLACE);
            assertThat(result.get("pet")).isEqualTo(List.of());
            assertThat(result.get("state")).isEqualTo("partial");
            assertThat(result.get("facilities").toString()).contains("상시 개방");
            assertThat(result.get("sources").toString()).contains("unavailable");
        }
    }

    @Test
    void failuresUseDatedStaleDataAndCooldownInsteadOfErasingFacts() throws Exception {
        try (var provider = new Provider()) {
            var service = provider.service();
            service.get(PLACE);
            long old = System.currentTimeMillis() - Duration.ofHours(25).toMillis();
            provider.jdbc.update("UPDATE tour_detail_cache SET checked_at=?", old);
            provider.fail = true;
            var result = service.get(PLACE);
            assertThat(result.get("state")).isEqualTo("partial");
            assertThat(result.get("pet").toString()).contains("목줄 착용");
            assertThat(result.get("sources").toString()).contains("state=stale", "checkedAt=" + old);
            int calls = provider.calls.get();
            service.get(PLACE);
            assertThat(provider.calls.get()).isEqualTo(calls);
        }
    }

    @Test
    void expiredFactsAreNotPresentedAsCurrent() throws Exception {
        try (var provider = new Provider()) {
            var service = provider.service(); service.get(PLACE);
            provider.jdbc.update("UPDATE tour_detail_cache SET checked_at=?", System.currentTimeMillis() - Duration.ofDays(8).toMillis());
            provider.fail = true;
            var result = service.get(PLACE);
            assertThat(result.get("state")).isEqualTo("unavailable");
            assertThat(result.get("pet")).isEqualTo(List.of());
            assertThat(result.get("overview")).isEqualTo("");
        }
    }

    @Test
    void knownRecoveryMatchesRequireUnambiguousNameAndLocation() throws Exception {
        try (var provider = new Provider()) {
            var recovered = new java.util.LinkedHashMap<>(PLACE); recovered.put("source", "recovered-tour-api");
            var result = provider.service().get(recovered);
            assertThat(result.get("contentId")).isEqualTo("127291");
            assertThat(provider.calls.get()).isEqualTo(5);
        }
        try (var provider = new Provider()) {
            provider.ambiguous = true;
            var recovered = new java.util.LinkedHashMap<>(PLACE); recovered.put("source", "recovered-tour-api");
            assertThat(provider.service().get(recovered).get("state")).isEqualTo("unmatched");
            assertThat(provider.calls.get()).isEqualTo(1);
        }
        assertThat(TourDetails.samePlace(PLACE, MAPPER.valueToTree(Map.of("contentid", "12", "title", "동막해변", "addr1", "제주", "mapx", "126.2", "mapy", "33.2")))).isFalse();
        assertThat(TourDetails.samePlace(PLACE, MAPPER.valueToTree(Map.of("contentid", "12", "title", "다른 장소", "addr1", "인천 강화")))).isFalse();
    }

    @Test
    void noConfigurationSubmissionsAndCallBudgetsDoNotTriggerExternalRequests() throws Exception {
        try (var provider = new Provider()) {
            var noKey = new TourDetails(provider.jdbc, MAPPER, "", provider.url(), provider.url(), 100, 4);
            noKey.initialize();
            assertThat(noKey.get(PLACE).get("state")).isEqualTo("not-configured");
            assertThat(provider.service().get(Map.of("source", "submission")).get("state")).isEqualTo("unmatched");
            assertThat(provider.service(100, 0).get(PLACE).get("state")).isEqualTo("unavailable");
            assertThat(provider.calls.get()).isZero();
        }
    }

    @Test
    void providerTimeoutDoesNotHangTheDetailRequest() throws Exception {
        try (var provider = new Provider()) {
            provider.delay = 800;
            long start = System.nanoTime();
            var result = provider.service(150, 100).get(PLACE);
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1400);
            assertThat(result.get("state")).isEqualTo("unavailable");
        }
    }

    @Test
    void homepageAndTextNeverCarryExecutableMarkup() {
        assertThat(TourDetails.homepage("<a href='javascript:alert(1)'>click</a>")).isEmpty();
        assertThat(TourDetails.homepage("<a href='https://trusted.example@evil.test/'>click</a>")).isEmpty();
        assertThat(TourDetails.homepage("시설 https://example.com/path")).isEqualTo("https://example.com/path");
        assertThat(TourDetails.plain("<style>hidden</style><img src=x onerror=alert(1)>내용<br>다음 줄")).contains("내용", "다음 줄").doesNotContain("onerror", "hidden", "<");
    }
}
