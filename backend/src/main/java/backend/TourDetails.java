package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class TourDetails {
    private static final long FRESH_MS = Duration.ofHours(24).toMillis();
    private static final long STALE_MS = Duration.ofDays(7).toMillis();
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String key, baseUrl, accessBaseUrl;
    private final int timeoutMs, maxCalls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, CompletableFuture<Result>> inFlight = new LinkedHashMap<>();
    private LocalDate budgetDate = LocalDate.now();
    private int calls;

    record Result(String state, JsonNode items, long checkedAt) {
        JsonNode first() { return items.isArray() && !items.isEmpty() ? items.get(0) : items.path("missing"); }
    }

    public TourDetails(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${soomgil.tour-api-key:}") String key,
            @Value("${soomgil.tour-base-url:https://apis.data.go.kr/B551011/KorService2}") String baseUrl,
            @Value("${soomgil.tour-access-base-url:https://apis.data.go.kr/B551011/KorWithService2}") String accessBaseUrl,
            @Value("${soomgil.tour-detail-timeout-ms:3000}") int timeoutMs,
            @Value("${soomgil.tour-detail-daily-limit:400}") int maxCalls) {
        this.jdbc = jdbc; this.mapper = mapper; this.key = key;
        this.baseUrl = baseUrl; this.accessBaseUrl = accessBaseUrl;
        this.timeoutMs = Math.max(100, timeoutMs); this.maxCalls = Math.max(0, maxCalls);
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tour_detail_cache (cache_key VARCHAR(160) PRIMARY KEY, payload LONGTEXT NOT NULL, checked_at BIGINT NOT NULL, retry_after BIGINT NOT NULL, last_state VARCHAR(30) NOT NULL)");
    }

    public Map<String, Object> get(Map<String, Object> place) {
        if (key.isBlank()) return unavailable("not-configured");
        String source = String.valueOf(place.getOrDefault("source", ""));
        String id;
        if (source.matches("tour-api:[0-9]{1,20}")) id = source.substring(9);
        else if (source.equals("recovered-tour-api") || source.equals("rebuild-seed")) {
            var match = load("match:" + signature(place), baseUrl, "searchKeyword2",
                    Map.of("keyword", String.valueOf(place.get("name")), "numOfRows", "30"), "").join();
            var candidates = new ArrayList<String>();
            for (JsonNode item : match.items()) if (samePlace(place, item)) candidates.add(item.path("contentid").asText());
            if (candidates.size() != 1) return unavailable(match.state().equals("ok") || match.state().equals("empty") ? "unmatched" : match.state());
            id = candidates.get(0);
        } else return unavailable("unmatched");

        // Independent providers share a bounded in-flight pool; one failure cannot erase another's data.
        var commonFuture = load("common:" + id, baseUrl, "detailCommon2", Map.of("contentId", id), id);
        boolean course = "course".equals(place.get("contentKind"));
        var petFuture = course ? CompletableFuture.completedFuture(empty("empty")) : load("pet:" + id, baseUrl, "detailPetTour2", Map.of("contentId", id), id);
        var accessFuture = course ? CompletableFuture.completedFuture(empty("empty")) : load("access:" + id, accessBaseUrl, "detailWithTour2", Map.of("contentId", id), id);
        Result common = commonFuture.join();
        String type = common.first().path("contenttypeid").asText();
        Result intro = type.matches("12|14|15|25|28|32|38|39")
                ? load("intro:" + id + ":" + type, baseUrl, "detailIntro2", Map.of("contentId", id, "contentTypeId", type), id).join()
                : empty("unavailable");
        return present(id, common, intro, petFuture.join(), accessFuture.join());
    }

    private synchronized CompletableFuture<Result> load(String cacheKey, String base, String operation, Map<String, String> query, String expectedId) {
        if (inFlight.containsKey(cacheKey)) return inFlight.get(cacheKey);
        Result previous = empty("unavailable");
        long now = System.currentTimeMillis();
        var rows = jdbc.queryForList("SELECT * FROM tour_detail_cache WHERE cache_key=?", cacheKey);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            try {
                long checkedAt = ((Number) row.get("checked_at")).longValue();
                previous = new Result(String.valueOf(row.get("last_state")), mapper.readTree(String.valueOf(row.get("payload"))), checkedAt);
                if (now < ((Number) row.get("retry_after")).longValue()) return CompletableFuture.completedFuture(fallback(previous, previous.state()));
                if (checkedAt > 0 && now - checkedAt < FRESH_MS) return CompletableFuture.completedFuture(new Result(previous.items().isEmpty() ? "empty" : "ok", previous.items(), checkedAt));
            } catch (Exception ignored) { previous = empty("unavailable"); }
        }
        if (!budgetDate.equals(LocalDate.now())) { budgetDate = LocalDate.now(); calls = 0; }
        if (calls >= maxCalls) return CompletableFuture.completedFuture(fallback(previous, "quota"));
        if (inFlight.size() >= 6) return CompletableFuture.completedFuture(fallback(previous, "busy"));
        calls++;
        Map<String, String> params = new LinkedHashMap<>(query);
        params.put("serviceKey", key); params.put("MobileOS", "ETC"); params.put("MobileApp", "Soomgil"); params.put("_type", "json");
        String encoded = params.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(base + "/" + operation + "?" + encoded)).timeout(Duration.ofMillis(timeoutMs)).GET().build();
        Result saved = previous;
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var future = pending.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).handle((response, failure) -> {
            Result result;
            long retryAfter = 0;
            try {
                if (failure != null) { pending.cancel(true); throw new IllegalStateException(); }
                if (response.statusCode() != 200 || response.body().length() > 262144) throw new IllegalStateException();
                JsonNode root = mapper.readTree(response.body()).path("response");
                if (!List.of("0000", "00").contains(root.path("header").path("resultCode").asText())) throw new IllegalStateException();
                if (!root.path("body").isObject()) throw new IllegalStateException();
                JsonNode value = root.path("body").path("items").path("item");
                var items = mapper.createArrayNode();
                if (value.isArray()) items.addAll((com.fasterxml.jackson.databind.node.ArrayNode) value);
                else if (value.isObject()) items.add(value);
                if (!expectedId.isBlank()) for (JsonNode item : items) {
                    if (!expectedId.equals(item.path("contentid").asText())) throw new IllegalStateException();
                }
                result = new Result(items.isEmpty() ? "empty" : "ok", items, System.currentTimeMillis());
            } catch (Exception ignored) {
                // Never log provider URLs/errors: the request contains a server-only service key.
                result = fallback(saved, "unavailable");
                retryAfter = System.currentTimeMillis() + 60000;
            }
            try { store(cacheKey, result, retryAfter); } catch (org.springframework.dao.DataAccessException ignored) { /* The live response can still be used. */ }
            return result;
        });
        inFlight.put(cacheKey, future);
        future.whenComplete((result, error) -> { synchronized (this) { inFlight.remove(cacheKey); } });
        return future;
    }

    private void store(String key, Result result, long retryAfter) {
        int updated = jdbc.update("UPDATE tour_detail_cache SET payload=?,checked_at=?,retry_after=?,last_state=? WHERE cache_key=?",
                result.items().toString(), result.checkedAt(), retryAfter, result.state(), key);
        if (updated == 0) {
            try { jdbc.update("INSERT INTO tour_detail_cache(cache_key,payload,checked_at,retry_after,last_state) VALUES(?,?,?,?,?)",
                    key, result.items().toString(), result.checkedAt(), retryAfter, result.state()); }
            catch (org.springframework.dao.DuplicateKeyException ignored) { /* A different server populated the same public cache. */ }
        }
    }

    private Result fallback(Result previous, String reason) {
        if (previous.checkedAt() > 0 && System.currentTimeMillis() - previous.checkedAt() < STALE_MS)
            return new Result(previous.items().isEmpty() ? reason : "stale", previous.items(), previous.checkedAt());
        return empty(reason);
    }

    private Result empty(String state) { return new Result(state, mapper.createArrayNode(), 0); }

    private Map<String, Object> unavailable(String state) {
        return Map.of("state", state, "overview", "", "facilities", List.of(), "pet", List.of(), "accessibility", List.of(), "sources", List.of());
    }

    Map<String, Object> present(String id, Result common, Result intro, Result pet, Result access) {
        JsonNode info = intro.first();
        var facilities = new ArrayList<Map<String, String>>();
        if ("25".equals(common.first().path("contenttypeid").asText())) {
            add(facilities, "distance", "코스 길이", first(info,"distance"));
            add(facilities, "duration", "소요시간", first(info,"taketime"));
        }
        add(facilities, "hours", "이용시간", first(info, "usetime", "usetimeculture", "usetimeleports", "opentimefood", "opentime", "playtime"));
        add(facilities, "season", "이용시기", first(info, "useseason"));
        add(facilities, "closed", "휴무일", first(info, "restdate", "restdateculture", "restdateleports", "restdatefood", "restdateshopping"));
        add(facilities, "parking", "주차", first(info, "parking", "parkingculture", "parkingleports", "parkingfood", "parkingshopping", "parkinglodging"));
        add(facilities, "phone", "문의처", first(info, "infocenter", "infocenterculture", "infocenterleports", "infocenterfood", "infocentershopping", "infocenterlodging", "sponsor1tel"));
        if (facilities.stream().noneMatch(row -> row.get("id").equals("phone"))) add(facilities, "phone", "문의처", first(common.first(), "tel"));
        add(facilities, "fee", "이용요금", first(info, "usefee", "usefeefood", "usefeeleports", "usetimefestival"));
        add(facilities, "experience", "체험안내", first(info, "expguide"));
        add(facilities, "menu", "대표메뉴", first(info, "firstmenu", "treatmenu"));
        add(facilities, "reservation", "예약안내", first(info, "reservation", "reservationfood", "reservationlodging", "bookingplace"));
        add(facilities, "checkin", "입실", first(info, "checkintime"));
        add(facilities, "checkout", "퇴실", first(info, "checkouttime"));
        var pets = new ArrayList<Map<String, String>>();
        add(pets, "intro", "기본 동반 안내", first(info, "chkpet", "chkpetculture", "chkpetleports", "chkpetshopping"));
        for (var field : List.of(new String[]{"acmpyTypeCd", "동반 구역"}, new String[]{"acmpyPsblCpam", "동반 대상"},
                new String[]{"acmpyNeedMtr", "필수 준비"}, new String[]{"etcAcmpyInfo", "동반 조건"},
                new String[]{"relaAcdntRiskMtr", "주의사항"}, new String[]{"relaPosesFclty", "관련 시설"}))
            add(pets, field[0], field[1], first(pet.first(), field[0]));
        var accessible = new ArrayList<Map<String, String>>();
        for (var field : List.of(new String[]{"parking", "장애인 주차"}, new String[]{"route", "접근로"}, new String[]{"exit", "출입구"},
                new String[]{"restroom", "화장실"}, new String[]{"wheelchair", "휠체어"}, new String[]{"elevator", "엘리베이터"},
                new String[]{"publictransport", "대중교통"}, new String[]{"ticketoffice", "매표소"}, new String[]{"room", "객실"},
                new String[]{"auditorium", "관람석"}, new String[]{"handicapetc", "기타 접근성"}, new String[]{"braileblock", "점자블록"},
                new String[]{"helpdog", "보조견"}, new String[]{"guidehuman", "안내 인력"}, new String[]{"audioguide", "음성 안내"},
                new String[]{"bigprint", "큰 글씨"}, new String[]{"brailepromotion", "점자 안내"}, new String[]{"guidesystem", "유도 안내"},
                new String[]{"blindhandicapetc", "시각 지원"}, new String[]{"signguide", "수어 안내"}, new String[]{"videoguide", "영상 안내"},
                new String[]{"hearingroom", "청각 지원 객실"}, new String[]{"hearinghandicapetc", "청각 지원"}, new String[]{"stroller", "유모차"},
                new String[]{"lactationroom", "수유실"}, new String[]{"babysparechair", "유아 의자"}, new String[]{"infantsfamilyetc", "영유아 편의"}))
            add(accessible, field[0], field[1], first(access.first(), field[0]));
        var sources = List.of(source("common", "장소 소개", common), source("intro", "이용 안내", intro),
                source("pet", "반려동물", pet), source("access", "접근성", access));
        String overview = plain(common.first().path("overview").asText());
        boolean hasInfo = !overview.isBlank() || !facilities.isEmpty() || !pets.isEmpty() || !accessible.isEmpty();
        boolean failed = List.of(common, intro, pet, access).stream().anyMatch(r -> !List.of("ok", "empty").contains(r.state()));
        var result = new LinkedHashMap<String, Object>();
        result.put("state", hasInfo ? failed ? "partial" : "ready" : failed ? "unavailable" : "empty");
        result.put("contentId", id); result.put("overview", overview); result.put("facilities", facilities);
        result.put("pet", pets); result.put("accessibility", accessible); result.put("sources", sources);
        result.put("homepage", homepage(common.first().path("homepage").asText()));
        result.put("providerUpdatedAt", plain(common.first().path("modifiedtime").asText()));
        return result;
    }

    private Map<String, Object> source(String id, String label, Result result) {
        return Map.of("id", id, "label", label, "state", result.state(), "checkedAt", result.checkedAt());
    }

    private String first(JsonNode item, String... fields) {
        for (String field : fields) { String value = plain(item.path(field).asText()); if (!value.isBlank()) return value; }
        return "";
    }

    private void add(List<Map<String, String>> rows, String id, String label, String value) {
        if (!value.isBlank()) rows.add(Map.of("id", id, "label", label, "value", value));
    }

    static String plain(String html) {
        var doc = Jsoup.parseBodyFragment(html == null ? "" : html.substring(0, Math.min(html.length(), 16000)));
        doc.select("script,style,iframe,object").remove();
        doc.select("br").before("\n");
        String text = doc.body().wholeText().replace('\u00a0', ' ').trim();
        return text.substring(0, Math.min(8000, text.length()));
    }

    static String homepage(String html) {
        var doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        String link = doc.select("a[href]").stream().map(a -> a.attr("href")).filter(TourDetails::webUrl).findFirst().orElse("");
        if (!link.isEmpty()) return link;
        var match = URL.matcher(doc.text());
        return match.find() && webUrl(match.group()) ? match.group() : "";
    }

    private static boolean webUrl(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 2000 && List.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().contains(".") && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    static boolean samePlace(Map<String, Object> place, JsonNode item) {
        if (!item.path("contentid").asText().matches("[0-9]{1,20}")) return false;
        if (!fold(String.valueOf(place.get("name"))).equals(fold(item.path("title").asText()))) return false;
        String address = fold(String.valueOf(place.getOrDefault("address", "")));
        if (!address.isBlank() && address.equals(fold(item.path("addr1").asText()))) return true;
        try {
            double x = Double.parseDouble(String.valueOf(place.get("mapx"))), y = Double.parseDouble(String.valueOf(place.get("mapy")));
            double otherX = item.path("mapx").asDouble(), otherY = item.path("mapy").asDouble();
            return x > 124 && x < 132 && y > 32 && y < 39 && Math.hypot((x - otherX) * 88, (y - otherY) * 111) <= 0.15;
        } catch (NumberFormatException e) { return false; }
    }

    private static String fold(String text) { return text.replaceAll("[^\\p{L}\\p{N}]", ""); }

    private static String signature(Map<String, Object> place) {
        try {
            String text = List.of("id", "name", "address", "mapx", "mapy").stream().map(k -> String.valueOf(place.get(k))).collect(java.util.stream.Collectors.joining("|"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
