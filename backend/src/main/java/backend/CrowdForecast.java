package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CrowdForecast {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String key, baseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate budgetDate = today();
    private int dailyCalls;
    private long retryAfter;
    record Prediction(double value, String label, String date) {}
    private record Cached(Prediction prediction, long checkedAt) {}

    public CrowdForecast(JdbcTemplate jdbc, ObjectMapper mapper, @Value("${soomgil.tour-api-key:}") String key,
                         @Value("${soomgil.crowd-base-url:https://apis.data.go.kr/B551011/TatsCnctrRateService}") String baseUrl) {
        this.jdbc = jdbc; this.mapper = mapper; this.key = key; this.baseUrl = baseUrl;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tour_crowd_cache (cache_key VARCHAR(400) PRIMARY KEY, payload LONGTEXT, checked_at BIGINT)");
        long cutoff = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
        jdbc.update("DELETE FROM tour_crowd_cache WHERE checked_at<?", cutoff);
        for (var row : jdbc.queryForList("SELECT * FROM tour_crowd_cache WHERE checked_at>=? ORDER BY checked_at DESC LIMIT 1000", cutoff)) {
            try { cache.put(String.valueOf(row.get("cache_key")), new Cached(mapper.readValue(String.valueOf(row.get("payload")), Prediction.class), ((Number) row.get("checked_at")).longValue())); }
            catch (Exception ignored) { /* Invalid cache rows are refetched, never treated as quiet. */ }
        }
    }

    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
    static String label(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) return "";
        // Restored from the original getCrowdLevel implementation (June 2026).
        return value < 40 ? "한적함" : value < 70 ? "보통" : "붐빔";
    }
    private static String folded(String name) { return name.replaceAll("[\\s\\p{Punct}]", ""); }
    static Prediction parse(JsonNode items, String name, String area, String district, LocalDate date) {
        var rows = items.isArray() ? items : new ObjectMapper().createArrayNode().add(items);
        for (var row : rows) {
            if (!folded(row.path("tAtsNm").asText()).equals(folded(name))
                    || !row.path("areaCd").asText().equals(area) || !row.path("signguCd").asText().equals(district)
                    || !row.path("baseYmd").asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))) continue;
            try {
                double value = Double.parseDouble(row.path("cnctrRate").asText());
                if (!label(value).isEmpty()) return new Prediction(value, label(value), date.toString());
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    private String cacheKey(Map<String, Object> place) { return place.get("legalAreaCode") + ":" + place.get("legalDistrictCode") + ":" + place.get("name"); }

    public void apply(Map<String, Object> place) {
        place.putIfAbsent("crowdStatus", "unrequested");
        var entry = cache.get(cacheKey(place));
        if (entry != null && entry.prediction() == null && entry.checkedAt() + Duration.ofMinutes(30).toMillis() > System.currentTimeMillis()) place.put("crowdStatus", "no-data");
        if (entry == null || entry.prediction() == null || !entry.prediction().date().equals(today().toString()) || label(entry.prediction().value()).isEmpty()) return;
        var prediction = entry.prediction();
        place.put("crowd", label(prediction.value())); place.put("level", label(prediction.value())); place.put("crowdValue", prediction.value());
        place.put("crowdAvailable", true); place.put("crowdVerified", false); place.put("crowdBasis", "forecast");
        place.put("crowdSource", "tourapi-concentration"); place.put("crowdDate", prediction.date()); place.put("crowdCheckedAt", entry.checkedAt());
        place.put("crowdStatus", "ready");
        place.put("crowdBackupAt", null); place.put("crowdCollectedAt", null);
    }

    public void refresh(Map<String, Object> place) {
        String area = String.valueOf(place.getOrDefault("legalAreaCode", "")), district = String.valueOf(place.getOrDefault("legalDistrictCode", ""));
        String name = String.valueOf(place.getOrDefault("name", ""));
        if (key.isBlank()) { place.put("crowdStatus", "unavailable"); return; }
        if (!area.matches("\\d{2}") || !district.matches("\\d{5}") || !district.startsWith(area) || name.isBlank()) { place.put("crowdStatus", "unmatched"); return; }
        if (!lock.tryLock()) { place.put("crowdStatus", "busy"); return; }
        try {
            long now = System.currentTimeMillis();
            String cacheKey = cacheKey(place);
            var entry = cache.get(cacheKey);
            long ttl = entry != null && entry.prediction() != null ? Duration.ofHours(12).toMillis() : Duration.ofMinutes(30).toMillis();
            if (entry != null && entry.checkedAt() + ttl > now && (entry.prediction() == null || entry.prediction().date().equals(today().toString()))) return;
            if (retryAfter > now) { place.put("crowdStatus", "unavailable"); return; }
            if (!budgetDate.equals(today())) { dailyCalls = 0; budgetDate = today(); }
            if (dailyCalls >= 200) { place.put("crowdStatus", "limited"); return; }
            dailyCalls++;
            Map<String, String> params = new LinkedHashMap<>(Map.of("serviceKey", key, "MobileOS", "ETC", "MobileApp", "Soomgil", "_type", "json", "numOfRows", "30", "pageNo", "1", "areaCd", area, "signguCd", district, "tAtsNm", name));
            String query = params.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/tatsCnctrRatedList?" + query)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { place.put("crowdStatus", "unavailable"); retryAfter = now + 60000; return; }
            var root = mapper.readTree(response.body()).path("response");
            if (!List.of("0000", "00").contains(root.path("header").path("resultCode").asText())) { place.put("crowdStatus", "unavailable"); retryAfter = now + 60000; return; }
            var prediction = parse(root.path("body").path("items").path("item"), name, area, district, today());
            String payload = mapper.writeValueAsString(prediction);
            int updated = jdbc.update("UPDATE tour_crowd_cache SET payload=?,checked_at=? WHERE cache_key=?", payload, now, cacheKey);
            if (updated == 0) jdbc.update("INSERT INTO tour_crowd_cache VALUES(?,?,?)", cacheKey, payload, now);
            if (cache.size() >= 1000) cache.clear();
            cache.put(cacheKey, new Cached(prediction, now));
        } catch (InterruptedException exception) { place.put("crowdStatus", "unavailable"); Thread.currentThread().interrupt(); }
        catch (Exception exception) { place.put("crowdStatus", "unavailable"); retryAfter = System.currentTimeMillis() + 60000; }
        finally { lock.unlock(); }
    }
}
