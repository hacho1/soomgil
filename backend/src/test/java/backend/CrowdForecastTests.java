package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CrowdForecastTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void calculatesBandsOnlyForValidValues() {
        assertThat(CrowdForecast.label(0)).isEqualTo("한적함");
        assertThat(CrowdForecast.label(35)).isEqualTo("한적함");
        assertThat(CrowdForecast.label(39.99)).isEqualTo("한적함");
        assertThat(CrowdForecast.label(40)).isEqualTo("보통");
        assertThat(CrowdForecast.label(69.99)).isEqualTo("보통");
        assertThat(CrowdForecast.label(70)).isEqualTo("붐빔");
        assertThat(CrowdForecast.label(100)).isEqualTo("붐빔");
        for (double value : new double[]{999, -1, Double.NaN, Double.POSITIVE_INFINITY}) assertThat(CrowdForecast.label(value)).isEmpty();
    }

    @Test
    void neverUsesADifferentPlaceDistrictDayOrMissingValue() {
        var row = mapper.createObjectNode().put("tAtsNm", "간현관광지").put("areaCd", "51").put("signguCd", "51130").put("baseYmd", "20260913").put("cnctrRate", "71.22");
        var day = LocalDate.of(2026, 9, 13);
        assertThat(CrowdForecast.parse(row, "간현관광지", "51", "51130", day).label()).isEqualTo("붐빔");
        assertThat(CrowdForecast.parse(row, "다른 관광지", "51", "51130", day)).isNull();
        assertThat(CrowdForecast.parse(row, "간현관광지", "51", "51150", day)).isNull();
        assertThat(CrowdForecast.parse(row, "간현관광지", "51", "51130", day.plusDays(1))).isNull();
        for (String value : new String[]{"999", "", "NaN", "not-available"}) {
            row.put("cnctrRate", value);
            assertThat(CrowdForecast.parse(row, "간현관광지", "51", "51130", day)).isNull();
        }
    }

    @Test
    void fetchesOnceAndPersistsForecastWithoutChangingTheRating() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tatsCnctrRatedList", exchange -> {
            calls.incrementAndGet();
            var row = Map.of("tAtsNm", "간현관광지", "areaCd", "51", "signguCd", "51130", "baseYmd", CrowdForecast.today().format(DateTimeFormatter.BASIC_ISO_DATE), "cnctrRate", "71.22");
            byte[] data = mapper.writeValueAsBytes(Map.of("response", Map.of("header", Map.of("resultCode", "0000"), "body", Map.of("items", Map.of("item", java.util.List.of(row))))));
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var service = new CrowdForecast(jdbc, mapper, "test-key", base); service.initialize();
            Map<String, Object> place = new LinkedHashMap<>(Map.of("name", "간현관광지", "legalAreaCode", "51", "legalDistrictCode", "51130", "source", "tour-api:123", "hiddenRating", 4.3));
            service.refresh(place); service.refresh(place); service.apply(place);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(place).containsEntry("crowd", "붐빔").containsEntry("crowdBasis", "forecast").containsEntry("crowdVerified", false).containsEntry("hiddenRating", 4.3).containsEntry("crowdStatus", "ready");
            var restored = new CrowdForecast(jdbc, mapper, "test-key", base); restored.initialize(); restored.refresh(place); restored.apply(place);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(place.get("crowdValue")).isEqualTo(71.22);
        } finally { server.stop(0); jdbc.execute("SHUTDOWN"); }
    }

    @Test
    void upstreamFailureKeepsExistingObservationAndUsesCooldown() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tatsCnctrRatedList", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            var service = new CrowdForecast(org.mockito.Mockito.mock(JdbcTemplate.class), mapper, "test-key", "http://127.0.0.1:" + server.getAddress().getPort());
            Map<String, Object> place = new LinkedHashMap<>(Map.of("name", "순포해변", "legalAreaCode", "51", "legalDistrictCode", "51150", "crowd", "한적함", "crowdBasis", "archived"));
            service.refresh(place); service.refresh(place); service.apply(place);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(place).containsEntry("crowdBasis", "archived").containsEntry("crowdStatus", "unavailable");
        } finally { server.stop(0); }
    }

    @Test
    void onlyAnEmptySuccessfulResponseBecomesNoData() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tatsCnctrRatedList", exchange -> {
            byte[] data = mapper.writeValueAsBytes(Map.of("response", Map.of("header", Map.of("resultCode", "0000"), "body", Map.of("items", "", "totalCount", 0))));
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        try {
            var service = new CrowdForecast(jdbc, mapper, "test-key", "http://127.0.0.1:" + server.getAddress().getPort()); service.initialize();
            Map<String, Object> place = new LinkedHashMap<>(Map.of("name", "없는 장소", "legalAreaCode", "51", "legalDistrictCode", "51130"));
            service.apply(place);
            assertThat(place).containsEntry("crowdStatus", "unrequested");
            service.refresh(place); service.apply(place);
            assertThat(place).containsEntry("crowdStatus", "no-data").doesNotContainKey("crowdValue");
            place.put("legalAreaCode", ""); service.refresh(place); service.apply(place);
            assertThat(place).containsEntry("crowdStatus", "unmatched");
        } finally { server.stop(0); jdbc.execute("SHUTDOWN"); }
    }

    @Test
    void persistedForecastLabelsAreRecomputedWithTheRestoredThresholds() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        try {
            var first = new CrowdForecast(jdbc, mapper, "", "http://localhost"); first.initialize();
            jdbc.update("INSERT INTO tour_crowd_cache VALUES(?,?,?)", "51:51130:간현관광지", mapper.writeValueAsString(new CrowdForecast.Prediction(71.22, "보통", CrowdForecast.today().toString())), System.currentTimeMillis());
            var restored = new CrowdForecast(jdbc, mapper, "", "http://localhost"); restored.initialize();
            Map<String, Object> place = new LinkedHashMap<>(Map.of("name", "간현관광지", "legalAreaCode", "51", "legalDistrictCode", "51130", "hiddenRating", 4.3));
            restored.apply(place);
            assertThat(place).containsEntry("crowd", "붐빔").containsEntry("hiddenRating", 4.3);
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
