package backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PlaceQueries {
    private final JdbcTemplate jdbc;
    private final SearchCatalog dictionary;
    public record Page(List<Map<String, Object>> rows, long total, int page, int size) {
        public boolean hasMore() { return (long) page * size < total; }
    }

    public PlaceQueries(JdbcTemplate jdbc, SearchCatalog dictionary) { this.jdbc = jdbc; this.dictionary = dictionary; }

    void initialize() {
        for (String column : List.of("search_text VARCHAR(4000)", "category_text VARCHAR(2000)", "region_text VARCHAR(2000)",
                "name_fold VARCHAR(600)", "map_x_num DOUBLE", "map_y_num DOUBLE", "tour_visible BOOLEAN DEFAULT TRUE",
                "tour_modified VARCHAR(14)", "tour_content_id VARCHAR(40)", "tour_content_type VARCHAR(2)", "content_kind VARCHAR(16)", "place_quality INT", "discovery_base INT", "discovery_interests INT",
                "discovery_seasons INT", "discovery_theme VARCHAR(40)", "discovery_topics INT", "discovery_region VARCHAR(120)", "discovery_eligible BOOLEAN", "discovery_version INT")) jdbc.execute("ALTER TABLE places ADD COLUMN IF NOT EXISTS " + column);
        jdbc.execute("CREATE INDEX IF NOT EXISTS places_source_idx ON places(source)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS places_title_idx ON places(name)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS places_tour_id_idx ON places(tour_content_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS places_location_idx ON places(approved,tour_visible,map_y_num,map_x_num)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS places_name_idx ON places(approved,tour_visible,name_fold,id)");
        // A discovery-only leading key keeps ordinary text searches on their existing index.
        jdbc.execute("CREATE INDEX IF NOT EXISTS places_discovery_idx ON places(discovery_eligible,approved,tour_visible,discovery_region,discovery_theme,discovery_base,discovery_seasons,discovery_interests,region_text,id)");
    }

    void backfill() {
        long last = 0;
        while (true) {
            var rows = jdbc.queryForList("SELECT * FROM places WHERE id>? AND (search_text IS NULL OR discovery_version IS NULL OR discovery_version<>?) ORDER BY id LIMIT 500", last, DiscoveryRecommendations.INDEX_VERSION);
            if (rows.isEmpty()) break;
            var arguments = rows.stream().map(this::indexArguments).toList();
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                boolean autoCommit = connection.getAutoCommit();
                if (autoCommit) connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement(INDEX_SQL)) {
                    for (var values : arguments) {
                        for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    if (autoCommit) connection.commit();
                } catch (java.sql.SQLException | RuntimeException failure) {
                    if (autoCommit) connection.rollback();
                    throw failure;
                } finally { if (autoCommit) connection.setAutoCommit(true); }
                return null;
            });
            last = ((Number) rows.get(rows.size()-1).get("id")).longValue();
        }
    }

    void index(long id) { jdbc.queryForList("SELECT * FROM places WHERE id=?", id).forEach(this::index); }
    void indexSource(String source) { jdbc.queryForList("SELECT * FROM places WHERE source=?", source).forEach(this::index); }
    private static String str(Map<String, Object> row, String key) { return row.get(key) == null ? "" : String.valueOf(row.get(key)); }
    private static final String INDEX_SQL = "UPDATE places SET search_text=?,category_text=?,region_text=?,name_fold=?,map_x_num=?,map_y_num=?,discovery_base=?,discovery_interests=?,discovery_seasons=?,discovery_theme=?,discovery_topics=?,discovery_region=?,discovery_eligible=?,discovery_version=?,content_kind=?,place_quality=? WHERE id=?";
    private void index(Map<String, Object> row) { jdbc.update(INDEX_SQL,indexArguments(row)); }
    private Object[] indexArguments(Map<String, Object> row) {
        var content = PlaceContent.classify(row);
        String name = str(row, "name"), address = str(row, "address"), region = str(row, "region"), tags = str(row, "tags"), category = content.label();
        String search = SearchCatalog.fold(name + " " + address + " " + region + " " + category + " " + tags);
        String theme = SearchCatalog.fold(name + " " + tags + " " + category);
        String area = SearchCatalog.fold(TourCatalog.regionName(region) + " " + TourCatalog.regionName(address));
        Double x = coordinate(str(row, "mapx"), 124, 132), y = coordinate(str(row, "mapy"), 32, 39);
        var discovery = DiscoveryRecommendations.indexValues(row);
        String discoveryRegion = str(row,"legal_area_code");
        if (discoveryRegion.isBlank()) discoveryRegion = region.isBlank() ? "unknown" : region;
        return new Object[]{search,theme,area,SearchCatalog.fold(name),x,y,discovery.base(),discovery.interests(),discovery.seasons(),discovery.theme(),dictionary.topicMask(row),discoveryRegion,PlaceContent.discoveryEligible(row),DiscoveryRecommendations.INDEX_VERSION,content.kind(),content.quality(),row.get("id")};
    }
    private static Double coordinate(String text, int min, int max) {
        try { double value = Double.parseDouble(text); return value > min && value < max ? value : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private String like(String column, String term, List<Object> args) {
        args.add("%" + SearchCatalog.fold(term).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        return column + " LIKE ? ESCAPE '!'";
    }
    private String matching(String column, String query, List<Object> args) {
        if (query.isBlank()) return "1=1";
        String literal = like(column, query, args);
        var clauses = new ArrayList<String>();
        for (var alternatives : dictionary.matchTerms(query)) {
            var terms = new ArrayList<String>();
            for (String term : alternatives) terms.add(like(column, term, args));
            clauses.add("(" + String.join(" OR ", terms) + ")");
        }
        return "(" + literal + " OR (" + String.join(" AND ", clauses) + "))";
    }
    private String where(String query, String region, String category, List<Object> args) {
        var clauses = new ArrayList<String>(List.of("p.approved=TRUE", "p.tour_visible=TRUE", matching("p.search_text", query, args)));
        if (!region.isBlank()) clauses.add(like("p.region_text", TourCatalog.regionName(region), args));
        if (!category.isBlank()) {
            var group = dictionary.categories().stream().filter(item -> item.id().equals(category)).findFirst().orElse(null);
            if (group == null) clauses.add("1=0");
            else {
                var themes = new ArrayList<String>();
                if (!group.types().isEmpty()) {
                    themes.add("p.category IN (" + group.types().stream().map(item -> "?").collect(Collectors.joining(",")) + ")");
                    args.addAll(group.types());
                }
                for (String topic : group.items()) themes.add(matching("p.category_text", topic, args));
                clauses.add("(" + String.join(" OR ", themes) + ")");
            }
        }
        return String.join(" AND ", clauses);
    }
    public Page search(String query, String region, String category, String sort, int page, int size) {
        return search(query, region, category, sort, page, size, DiscoveryRecommendations.Profile.guest());
    }
    public Page search(String query, String region, String category, String sort, int page, int size, DiscoveryRecommendations.Profile profile) {
        return search(query,region,category,sort,page,size,profile,"");
    }
    public Page search(String query, String region, String category, String sort, int page, int size, DiscoveryRecommendations.Profile profile, String kind) {
        if (!List.of("", "place", "course").contains(kind)) throw new IllegalArgumentException("Invalid content kind");
        var args = new ArrayList<Object>();
        String where = where(query, region, category, args);
        if (!kind.isEmpty()) { where += " AND p.content_kind=?"; args.add(kind); }
        Object[] filters = args.toArray();
        String order = "name".equals(sort) ? "p.name_fold,p.id" : DiscoveryRecommendations.searchOrder(query, dictionary.topicIndex(query), profile, CrowdForecast.today(), args);
        args.add(size); args.add((page - 1) * size);
        // Count and rank in one scan; load the full place records only for the requested page.
        String sql = "SELECT p.id,COUNT(*) OVER() AS result_total FROM places p WHERE " + where + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        var matched = jdbc.queryForList(sql, args.toArray());
        long total = matched.isEmpty() ? 0 : ((Number) matched.get(0).get("result_total")).longValue();
        int current = page;
        if (matched.isEmpty() && page > 1) {
            total = jdbc.queryForObject("SELECT COUNT(*) FROM places p WHERE " + where, Long.class, filters);
            current = Math.min(page, Math.max(1, (int) Math.ceil((double) total / size)));
            if (total > 0) {
                args.set(args.size() - 1, (current - 1) * size);
                matched = jdbc.queryForList(sql, args.toArray());
            }
        }
        var ids = matched.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        var rows = new java.util.HashMap<Long,Map<String,Object>>();
        publicRows(ids).forEach(row -> rows.put(((Number) row.get("id")).longValue(), row));
        return new Page(ids.stream().map(rows::get).filter(java.util.Objects::nonNull).toList(), total, current, size);
    }
    List<Map<String,Object>> discoveryCandidates(DiscoveryRecommendations.Profile profile, java.time.LocalDate day) {
        var args = new ArrayList<Object>();
        String score = DiscoveryRecommendations.baseScoreSql(profile,day,args);
        long multiplier = 48271 + Math.floorMod(day.toEpochDay(),997)*1000003;
        String rotation = "MOD(CAST(id AS DECIMAL(30,0))*" + multiplier + ",2147483647)";
        // Regional/theme slots preserve coverage before the bounded in-memory reranking.
        String sql = "SELECT * FROM (SELECT scored.*,ROW_NUMBER() OVER (PARTITION BY discovery_region,discovery_theme ORDER BY discovery_priority DESC," + rotation + ",id) AS region_rank FROM ("
                + "SELECT p.id," + score + " AS discovery_priority,p.discovery_theme,p.discovery_region FROM places p "
                + "WHERE p.approved=TRUE AND p.tour_visible=TRUE AND p.discovery_eligible=TRUE"
                + ") scored) candidates WHERE region_rank<=3 ORDER BY region_rank,discovery_priority DESC," + rotation + ",id LIMIT 360";
        var ranked = jdbc.queryForList(sql,args.toArray());
        var rows = new java.util.HashMap<Long,Map<String,Object>>();
        publicDiscoveryRows(ranked.stream().map(row -> ((Number)row.get("id")).longValue()).toList()).forEach(row -> rows.put(((Number)row.get("id")).longValue(),row));
        var result = new ArrayList<Map<String,Object>>();
        for (var rank : ranked) {
            var row = rows.get(((Number)rank.get("id")).longValue());
            if (row != null) { var candidate = new java.util.LinkedHashMap<>(row); candidate.putAll(rank); result.add(candidate); }
        }
        return result;
    }
    List<Map<String,Object>> publicDiscoveryRows(List<Long> ids) {
        if (ids.size()>360) throw new IllegalArgumentException("Discovery pool is bounded");
        return publicRows(ids);
    }
    private List<Map<String,Object>> publicRows(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT * FROM places WHERE approved=TRUE AND tour_visible=TRUE AND id IN (" + String.join(",",java.util.Collections.nCopies(ids.size(),"?")) + ")",ids.toArray());
    }
    public Page nearby(double x, double y, int radius, String category, String sort, int page, int size) {
        return nearby(x,y,radius,category,sort,page,size,false);
    }
    Page nearby(double x, double y, int radius, String category, String sort, int page, int size, boolean discoveryOnly) {
        var args = new ArrayList<Object>(List.of(y, y, x));
        String where = where("", "", category, args) + " AND COALESCE(p.content_kind,'unknown')<>'course'";
        if (discoveryOnly) where += " AND p.discovery_eligible=TRUE";
        double latitude = radius / 110000.0, longitude = latitude / Math.cos(Math.toRadians(y));
        where += " AND p.map_y_num BETWEEN ? AND ? AND p.map_x_num BETWEEN ? AND ?";
        args.addAll(List.of(y - latitude, y + latitude, x - longitude, x + longitude));
        String distance = "6371000*ACOS(LEAST(1,GREATEST(-1,SIN(RADIANS(p.map_y_num))*SIN(RADIANS(?))+COS(RADIANS(p.map_y_num))*COS(RADIANS(?))*COS(RADIANS(p.map_x_num-?)))))";
        String base = " FROM (SELECT p.*," + distance + " AS distance_meters FROM places p WHERE " + where + ") nearby WHERE distance_meters<=?";
        args.add(radius);
        long total = jdbc.queryForObject("SELECT COUNT(*)" + base, Long.class, args.toArray());
        int current = Math.min(page, Math.max(1, (int) Math.ceil((double) total / size)));
        String priority = "slow".equals(sort) ? "CASE WHEN category IN ('walk','nature') OR name LIKE '%습지%' OR name LIKE '%숲길%' OR name LIKE '%자연휴양림%' THEN 0 WHEN name LIKE '%해변%' OR name LIKE '%계곡%' OR name LIKE '%등대%' OR name LIKE '%정원%' THEN 1 WHEN category IN ('camping','culture') THEN 2 ELSE 3 END," : "";
        args.add(size); args.add((current - 1) * size);
        return new Page(jdbc.queryForList("SELECT *" + base + " ORDER BY " + priority + "distance_meters,id LIMIT ? OFFSET ?", args.toArray()), total, current, size);
    }
    public long publicCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE approved=TRUE AND tour_visible=TRUE", Long.class); }
}
