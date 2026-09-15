package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Consumer;
import java.time.ZoneId;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TourCatalog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TourCatalog.class);
    static final List<String> REGIONS = List.of("서울", "인천", "대전", "대구", "광주", "부산", "울산", "세종", "경기", "강원", "충북", "충남", "경북", "경남", "전북", "전남", "제주", "전남·광주");
    private static final List<String> AREA_CODES = List.of("1", "2", "3", "4", "5", "6", "7", "8", "31", "32", "33", "34", "35", "36", "37", "38", "39");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Map<String, CachedPage> cache = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SearchCatalog searchCatalog;
    private final String key;
    private final String baseUrl;
    private volatile String state;
    private volatile long lastSuccess;
    private long retryAfter;
    private final PlaceQueries queries;
    private final TransactionTemplate transactions;
    private final int dailyLimit;
    private volatile long nationalTotal;
    private record CachedPage(long until, Feed page) {}
    public record Feed(String state, long total, int page, int pageSize, boolean hasMore, List<Long> ids) {}

    public TourCatalog(JdbcTemplate jdbc, ObjectMapper mapper, SearchCatalog searchCatalog,
                       @Value("${soomgil.tour-api-key:}") String key,
                       @Value("${soomgil.tour-base-url:https://apis.data.go.kr/B551011/KorService2}") String baseUrl,
                       PlaceQueries queries, PlatformTransactionManager transactionManager,
                       @Value("${soomgil.tour-catalog-daily-limit:300}") int dailyLimit) {
        this.jdbc = jdbc;
        this.queries = queries; this.transactions = new TransactionTemplate(transactionManager);
        this.dailyLimit = Math.max(1, Math.min(1000, dailyLimit));
        this.mapper = mapper;
        this.searchCatalog = searchCatalog;
        this.key = key;
        this.baseUrl = baseUrl;
        this.state = key.isBlank() ? "not-configured" : "ready";
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tour_catalog_usage (usage_day VARCHAR(10) PRIMARY KEY, calls INT NOT NULL)");
    }
    public boolean configured() { return !key.isBlank(); }
    public int dailyCalls() {
        return jdbc.queryForList("SELECT calls FROM tour_catalog_usage WHERE usage_day=?", Integer.class, LocalDate.now(ZoneId.of("Asia/Seoul")).toString()).stream().findFirst().orElse(0);
    }
    public int dailyLimit() { return dailyLimit; }
    private boolean claimCall() {
        String day = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        try { jdbc.update("INSERT INTO tour_catalog_usage(usage_day,calls) SELECT ?,0 WHERE NOT EXISTS(SELECT 1 FROM tour_catalog_usage WHERE usage_day=?)", day, day); }
        catch (org.springframework.dao.DuplicateKeyException ignored) { }
        return jdbc.update("UPDATE tour_catalog_usage SET calls=calls+1 WHERE usage_day=? AND calls<?", day, dailyLimit) == 1;
    }
    public Map<String, Object> status() {
        return Map.of("state", state, "lastSuccessAt", lastSuccess, "catalogMode", "database-paged", "maxResultsPerRequest", 60, "nationalTotal", nationalTotal);
    }

    public static String regionName(String value) {
        if (List.of("광주", "광주광역시", "전남", "전라남도", "전남·광주", "전남광주통합", "전남광주통합특별시").contains(value.trim())) return "전남·광주";
        return value.replace("충청북", "충북").replace("충청남", "충남")
                .replace("경상북", "경북").replace("경상남", "경남")
                .replace("전라북", "전북").replace("전라남", "전남")
                .replace("특별자치도", "").replace("특별자치시", "")
                .replace("광역시", "").replace("특별시", "").replaceAll("도$", "").trim();
    }

    public static String areaCode(String region) {
        int index = REGIONS.indexOf(regionName(region));
        if ("전남·광주".equals(regionName(region))) return "38";
        return index < 0 ? "" : AREA_CODES.get(index);
    }

    public static String legalAreaCode(String region) {
        if ("전남·광주".equals(regionName(region))) return "12";
        if ("세종".equals(regionName(region))) return "36110";
        int index = REGIONS.indexOf(regionName(region));
        return index < 0 ? "" : List.of("11", "28", "30", "27", "29", "26", "31", "36", "41", "51", "43", "44", "47", "48", "52", "46", "50").get(index);
    }

    public void refresh(String query, String region, String category) {
        if (query.isBlank() && region.isBlank() && category.isBlank()) return;
            String keyword = searchCatalog.upstreamQuery(query);
            String searchRegion = region;
            String[] terms = keyword.split("\\s+", 2);
            if (terms.length >= 1) {
                String firstRegion = regionName(terms[0]);
                if (REGIONS.contains(firstRegion)) {
                    if (searchRegion.isBlank()) searchRegion = firstRegion;
                    keyword = terms.length == 2 ? terms[1] : "";
                }
            }
            if (keyword.isBlank()) keyword = searchCatalog.categoryKeyword(category);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("numOfRows", "60"); params.put("pageNo", "1"); params.put("arrange", "C");
            if (!keyword.isBlank()) params.put("keyword", keyword);
            // Nationwide requests deliberately omit areaCode.
            if (!legalAreaCode(searchRegion).isBlank()) params.put("lDongRegnCd", legalAreaCode(searchRegion));
            String contentType = searchCatalog.contentType(category);
            if (!contentType.isBlank()) params.put("contentTypeId", contentType);
            fetch(keyword.isBlank() ? "/areaBasedList2" : "/searchKeyword2", params);
    }

    public Feed discover(String region, String category, int page, Double x, Double y, int radius) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("numOfRows", "60"); params.put("pageNo", String.valueOf(page));
        params.put("arrange", x == null ? "C" : "E");
        if (!legalAreaCode(region).isBlank()) params.put("lDongRegnCd", legalAreaCode(region));
        String contentType = searchCatalog.contentType(category);
        if (!contentType.isBlank()) params.put("contentTypeId", contentType);
        if (x != null) {
            params.put("mapX", String.valueOf(x)); params.put("mapY", String.valueOf(y)); params.put("radius", String.valueOf(radius));
        }
        return fetch(x == null ? "/areaBasedList2" : "/locationBasedList2", params);
    }

    private record Remote(String state, long total, int page, int size, List<JsonNode> rows) {}
    public record SyncBatch(long total, int page, int size, int received, int skipped) {
        public boolean hasMore() { return (long) page * size < total; }
    }
    private Remote requestPage(String endpoint, Map<String, String> params) {
        int page = Integer.parseInt(params.get("pageNo")), size = Integer.parseInt(params.get("numOfRows"));
        if (!configured()) return new Remote("not-configured", 0, page, size, List.of());
        if (retryAfter > System.currentTimeMillis()) return new Remote("unavailable", 0, page, size, List.of());
        if (!claimCall()) { state = "budget-limited"; return new Remote(state, 0, page, size, List.of()); }
        try {
            var parameters = new LinkedHashMap<>(params);
            parameters.put("serviceKey", key); parameters.put("MobileOS", "ETC"); parameters.put("MobileApp", "Soomgil"); parameters.put("_type", "json");
            String encoded = parameters.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            var request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint + "?" + encoded)).timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new java.io.IOException("Upstream unavailable");
            JsonNode root = mapper.readTree(response.body()).path("response");
            if ("22".equals(root.path("header").path("resultCode").asText())) {
                jdbc.update("UPDATE tour_catalog_usage SET calls=? WHERE usage_day=?", dailyLimit, LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
                state = "budget-limited"; return new Remote(state, 0, page, size, List.of());
            }
            if (!List.of("0000", "00").contains(root.path("header").path("resultCode").asText())) throw new java.io.IOException("Upstream rejected request");
            JsonNode body = root.path("body");
            long total = body.path("totalCount").asLong(-1);
            long expectedRows = Math.max(0, Math.min(size, total - (long) (page - 1) * size));
            // The final page reports its actual row count instead of the requested page size.
            int reportedSize = body.path("numOfRows").asInt(size);
            if (total < 0 || (body.has("pageNo") && body.path("pageNo").asInt() != page)
                    || (reportedSize != size && reportedSize != expectedRows)) throw new java.io.IOException("Invalid pagination");
            var rows = new java.util.ArrayList<JsonNode>();
            JsonNode items = body.path("items").path("item");
            if (items.isArray()) items.forEach(rows::add);
            else if (items.isObject()) rows.add(items);
            if (rows.size() != expectedRows) throw new java.io.IOException("Incomplete page");
            lastSuccess = System.currentTimeMillis(); state = "connected";
            return new Remote(state, total, page, size, rows);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (java.io.IOException | IllegalArgumentException error) { log.warn("Tour catalog request failed ({})", error.getClass().getSimpleName()); }
        unavailable();
        return new Remote("unavailable", 0, page, size, List.of());
    }

    private Feed fetch(String endpoint, Map<String, String> params) {
        int page = Integer.parseInt(params.get("pageNo")), size = Integer.parseInt(params.get("numOfRows"));
        if (!refreshLock.tryLock()) return new Feed("busy", 0, page, size, false, List.of());
        try {
            String cacheKey = endpoint + params;
            var cached = cache.get(cacheKey);
            if (cached != null && cached.until() > System.currentTimeMillis()) return cached.page();
            var remote = requestPage(endpoint, params);
            if (!remote.state().equals("connected")) return new Feed(remote.state(), 0, page, size, false, List.of());
            var ids = transactions.execute(status -> {
                var result = new java.util.ArrayList<Long>();
                for (var item : remote.rows()) { Long id = save(item, false); if (id != null) result.add(id); }
                return List.copyOf(result);
            });
            if (endpoint.equals("/areaBasedList2") && !params.containsKey("lDongRegnCd") && !params.containsKey("contentTypeId")) nationalTotal = remote.total();
            var result = new Feed("connected", remote.total(), page, size, (long) page * size < remote.total(), ids);
            if (cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
            cache.put(cacheKey, new CachedPage(System.currentTimeMillis() + Duration.ofMinutes(30).toMillis(), result));
            return result;
        } catch (org.springframework.dao.DataAccessException error) {
            log.warn("Tour catalog save failed ({})", error.getClass().getSimpleName()); unavailable();
            return new Feed("unavailable", 0, page, size, false, List.of());
        } finally { refreshLock.unlock(); }
    }

    // Advance the durable checkpoint in the same transaction as these upserts.
    public String syncPage(int page, int size, String modifiedSince, Consumer<SyncBatch> checkpoint) {
        if (!refreshLock.tryLock()) return "busy";
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("pageNo", String.valueOf(page)); params.put("numOfRows", String.valueOf(size)); params.put("arrange", "A");
            if (modifiedSince.isBlank()) params.put("showflag", "1");
            else params.put("modifiedtime", modifiedSince);
            var remote = requestPage("/areaBasedSyncList2", params);
            if (!remote.state().equals("connected")) return remote.state();
            transactions.executeWithoutResult(status -> {
                int skipped = 0;
                for (var row : remote.rows()) {
                    if (!List.of("0", "1").contains(row.path("showflag").asText())) throw new IllegalArgumentException("Missing publication status");
                    if (save(row, true) == null) skipped++;
                }
                checkpoint.accept(new SyncBatch(remote.total(), page, size, remote.rows().size(), skipped));
            });
            if (modifiedSince.isBlank()) nationalTotal = remote.total();
            return "connected";
        } finally { refreshLock.unlock(); }
    }

    private void unavailable() { state = "unavailable"; retryAfter = System.currentTimeMillis() + 60000; }

    private Long save(JsonNode item, boolean sync) {
        String title = field(item, "title", 300), address = field(item, "addr1", 600), contentId = field(item, "contentid", 40);
        if (!contentId.matches("[0-9]+")) return null;
        String source = "tour-api:" + contentId, modified = field(item, "modifiedtime", 14);
        var existing = findTour(contentId);
        String oldId = field(item, "oldContentid", 40);
        if (existing.isEmpty() && oldId.matches("[0-9]+") && !oldId.equals(contentId))
            existing = findTour(oldId);
        if (existing.isEmpty() && !title.isBlank() && !address.isBlank())
            existing = jdbc.queryForList("SELECT * FROM places WHERE name=? AND address=? AND source IN ('recovered-tour-api','rebuild-seed') ORDER BY id", title, address);
        Long id = existing.isEmpty() ? null : ((Number) existing.get(0).get("id")).longValue();
        String previousModified = id == null || existing.get(0).get("tour_modified") == null ? "" : String.valueOf(existing.get(0).get("tour_modified"));
        if (!previousModified.isBlank() && (modified.isBlank() || modified.compareTo(previousModified) < 0)) return id;
        if (sync && "0".equals(item.path("showflag").asText())) {
            if (id != null) jdbc.update("UPDATE places SET tour_visible=FALSE,tour_modified=?,tour_content_id=?,source=? WHERE id=?", modified, contentId, source, id);
            return id;
        }
        if (title.isBlank()) return null;
        String image = field(item, "firstimage", 1000).replace("http://tong.visitkorea.or.kr/", "https://tong.visitkorea.or.kr/");
        if (!image.startsWith("https://tong.visitkorea.or.kr/")) image = "";
        String type = PlaceContent.validType(item.path("contenttypeid").asText("").trim());
        if (type.isEmpty() && id != null) type = PlaceContent.validType(PlaceContent.text(existing.get(0),"tour_content_type"));
        var classification = PlaceContent.classify(Map.of("tour_content_type",type,"name",title,"category","tour","category_label","관광지"));
        String category = classification.category(), label = classification.label();
        int areaIndex = AREA_CODES.indexOf(item.path("areacode").asText());
        String region = regionName(areaIndex >= 0 ? REGIONS.get(areaIndex) : address.split(" ")[0]);
        if (id == null) {
            jdbc.update("INSERT INTO places(name,address,region,category,category_label,image,mapx,mapy,approved,source,tags,tour_content_id,tour_modified,tour_visible) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)",
                    title, address, region, category, label, image, field(item,"mapx",80), field(item,"mapy",80), true, source, title, contentId, modified);
            id = jdbc.queryForObject("SELECT id FROM places WHERE tour_content_id=?", Long.class, contentId);
        } else {
            // Keep local identity, moderation, reviews, favorites, and user-written metadata.
            jdbc.update("UPDATE places SET name=?,address=?,region=?,category=?,category_label=?,image=?,mapx=?,mapy=?,source=?,tour_content_id=?,tour_modified=? WHERE id=?",
                    title, address, region, category, label, image, field(item,"mapx",80), field(item,"mapy",80), source, contentId, modified, id);
            if (sync) jdbc.update("UPDATE places SET tour_visible=TRUE WHERE id=?", id);
        }
        String area = field(item, "lDongRegnCd", 5), district = field(item, "lDongSignguCd", 5);
        if (!type.isEmpty()) jdbc.update("UPDATE places SET tour_content_type=? WHERE id=?", type,id);
        if (area.matches("\\d{2}") && district.matches("\\d{3}")) jdbc.update("UPDATE places SET legal_area_code=?,legal_district_code=? WHERE id=?", area, area + district, id);
        else if (area.equals("36110")) jdbc.update("UPDATE places SET legal_area_code=NULL,legal_district_code=NULL WHERE id=?", id);
        queries.index(id);
        return id;
    }

    private List<Map<String, Object>> findTour(String id) {
        var rows = jdbc.queryForList("SELECT * FROM places WHERE tour_content_id=?", id);
        return rows.isEmpty() ? jdbc.queryForList("SELECT * FROM places WHERE source=? ORDER BY id", "tour-api:" + id) : rows;
    }

    private String field(JsonNode item, String name, int max) {
        String value = item.path(name).asText("").trim();
        return value.substring(0, Math.min(max, value.length()));
    }
}
