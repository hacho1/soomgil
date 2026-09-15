package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explainable discovery ranking; neither a popularity measurement nor an AI-generated rating. */
@Service
public class DiscoveryRecommendations {
    public record Profile(Set<String> interests, Set<String> regions, String crowd, Set<Long> saved) {
        static Profile guest() { return new Profile(Set.of(), Set.of(), "normal", Set.of()); }
        boolean personalized() { return !interests.isEmpty() || !regions.isEmpty() || !crowd.equals("normal"); }
    }
    record Theme(String id, String label, List<String> terms, String description) { }
    private static final List<Theme> THEMES = List.of(
            new Theme("sea", "바다", List.of("해변", "해수욕장", "바다", "해안", "등대"), "해안 풍경을 둘러보는 여행 후보예요."),
            new Theme("walk", "산책", List.of("산책", "둘레길", "숲길", "탐방로", "걷기", "수목원", "정원", "공원"), "걸으며 둘러볼 장소를 찾는 일정에 어울려요."),
            new Theme("camping", "캠핑", List.of("캠핑", "야영", "글램핑", "카라반"), "캠핑 여행 후보예요. 운영일과 예약 가능 여부를 확인해주세요."),
            new Theme("mountain", "산", List.of("등산", "오름", "산림", "휴양림", "산자락"), "산과 숲을 찾는 여행에서 살펴볼 만해요."),
            new Theme("nature", "자연", List.of("계곡", "습지", "생태", "폭포", "자연", "호수", "숲", "수목원"), "자연 풍경을 중심으로 둘러보는 일정의 후보예요."),
            new Theme("cafe", "카페", List.of("카페", "커피", "찻집", "다방"), "주변 여행 중 쉬어갈 카페 후보예요."),
            new Theme("history", "역사", List.of("유적", "고택", "향교", "서원", "한옥", "사찰", "성곽", "역사"), "지역의 역사와 건축을 살펴보는 여행에 어울려요."),
            new Theme("museum", "박물관", List.of("박물관", "기념관"), "전시를 둘러보며 지역 이야기를 만날 수 있는 후보예요."),
            new Theme("culture", "문화·예술", List.of("미술관", "전시", "문화", "갤러리", "예술"), "전시와 문화 공간을 둘러보는 일정에 어울려요."),
            new Theme("food", "맛집", List.of("음식", "식당", "맛집", "국수", "식사"), "주변 여행 일정에 더할 식사 장소 후보예요."),
            new Theme("experience", "체험", List.of("체험", "공방", "농원", "목장"), "체험 일정을 찾을 때 살펴보세요. 프로그램 운영 여부는 별도 확인이 필요해요."),
            new Theme("photo", "사진", List.of("전망", "노을", "일몰", "일출", "야경", "포토"), "풍경을 기록할 장소를 찾는 일정에 어울려요."),
            new Theme("park", "공원", List.of("공원", "정원"), "공원과 정원을 둘러보는 일정의 후보예요."),
            new Theme("family", "가족", List.of("어린이", "가족", "아이와", "키즈"), "가족 여행 후보로 살펴보세요. 연령별 이용 조건은 확인이 필요해요."),
            new Theme("field-trip", "현장체험학습", List.of("학습", "교육", "과학관", "생태관", "체험"), "배움과 체험을 함께하는 여행 후보예요."),
            new Theme("tour", "관광지", List.of("관광", "명소"), "주변 일정에 함께 넣어볼 여행 후보예요."));
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TravelPreferences preferences;
    private final PlaceQueries queries;
    private final Clock clock;
    private record PoolKey(Set<String> interests, Set<String> regions, LocalDate day) { }
    private record Choice(long id, double priority) { }
    private record Pool(long createdAt, List<Choice> choices) { }
    private final Map<PoolKey,Pool> pools = new LinkedHashMap<>(16,.75f,true);
    private final Object[] poolLocks = java.util.stream.IntStream.range(0,16).mapToObj(i -> new Object()).toArray();
    static final int INDEX_VERSION = 3;
    static final String METHOD = "discovery-rules-v2";
    record IndexValues(int base, int interests, int seasons, String theme) { }

    static IndexValues indexValues(Map<String,Object> row) {
        var classification = PlaceContent.classify(row);
        String category = classification.category();
        String content = SearchCatalog.fold(text(row,"name") + text(row,"tags") + classification.label());
        int base = Set.of("walk","nature").contains(category) ? 14 : Set.of("camping","culture").contains(category) ? 8 : 0;
        if (List.of("숲길","둘레길","습지","생태","정원","계곡","해변","등대","고택").stream().anyMatch(content::contains)) base += 12;
        if (PlaceContent.officialImage(text(row,"image"))) base += 2;
        if (!text(row,"address").isBlank()) base += 2;
        int interests = 0, seasons = 0;
        String theme = Set.of("cafe","food","camping","experience").contains(category) ? category : "";
        for (int i=0;i<THEMES.size();i++) {
            Theme item = THEMES.get(i);
            boolean terms = item.terms().stream().map(SearchCatalog::fold).anyMatch(content::contains);
            if (item.id().equals(category) || terms) interests |= 1 << i;
            if (theme.isEmpty() && i<12 && terms) theme = item.id();
        }
        for (int i=0;i<4;i++) if (seasonTerms(LocalDate.of(2000,3+i*3,1)).stream().anyMatch(content::contains)) seasons |= 1 << i;
        return new IndexValues(base,interests,seasons,theme.isEmpty() ? (category.isBlank() ? "tour" : category) : theme);
    }
    static String bitSql(String column, int index) {
        // Decimal division and MOD work on both H2 and MariaDB, unlike vendor-specific bit operators.
        return "MOD(FLOOR(COALESCE(" + column + ",0)/" + (1 << index) + ".0),2)=1";
    }

    public DiscoveryRecommendations(JdbcTemplate jdbc, ObjectMapper mapper, TravelPreferences preferences, PlaceQueries queries, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.preferences = preferences; this.queries = queries; this.clock = clock;
    }
    LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    Profile profile(String user) {
        if (user.isBlank()) return Profile.guest();
        var preference = preferences.read(user);
        Set<String> interests = new LinkedHashSet<>();
        var settings = jdbc.queryForList("SELECT interests_json FROM user_settings WHERE user_id=?", user);
        if (!settings.isEmpty()) try { interests.addAll(preferences.normalizeInterests(mapper.readValue(String.valueOf(settings.get(0).get("interests_json")), new TypeReference<List<String>>() {}))); }
        catch (Exception ignored) { /* Old or invalid preferences never prevent browsing. */ }
        Set<String> regions = new LinkedHashSet<>();
        if (preference.get("preferredRegions") instanceof List<?> list) list.forEach(value -> regions.add(TourCatalog.regionName(String.valueOf(value))));
        return new Profile(Set.copyOf(interests), Set.copyOf(regions), String.valueOf(preference.get("crowdPreference")),
                new HashSet<>(jdbc.queryForList("SELECT place_id FROM favorites WHERE user_id=?", Long.class, user)));
    }
    static String text(Map<String, Object> place, String key) { return Objects.toString(place.get(key), ""); }
    static long id(Map<String, Object> place) { return ((Number) place.get("id")).longValue(); }
    private static String combined(Map<String, Object> place) { return SearchCatalog.fold(text(place, "name") + " " + text(place, "tags") + " " + text(place, "categoryLabel")); }
    private static boolean matches(Theme theme, Map<String, Object> place) {
        String content = combined(place);
        return theme.id().equals(text(place, "category")) || theme.terms().stream().anyMatch(content::contains);
    }
    private static Theme theme(Map<String, Object> place) {
        if (Set.of("cafe","food","camping","experience").contains(text(place,"category"))) return THEMES.stream().filter(item -> item.id().equals(text(place,"category"))).findFirst().orElseThrow();
        return THEMES.stream().filter(item -> matches(item, place)).findFirst().orElse(THEMES.get(THEMES.size() - 1));
    }
    static Set<String> interestsCovered() { var ids = new HashSet<>(THEMES.stream().map(Theme::id).toList()); ids.add("quiet"); return ids; }
    static List<String> seasonTerms(LocalDate date) {
        return switch (date.getMonthValue()) {
            case 3, 4, 5 -> List.of("꽃", "벚꽃", "수목원", "정원");
            case 6, 7, 8 -> List.of("해변", "해수욕장", "계곡", "캠핑");
            case 9, 10, 11 -> List.of("단풍", "억새", "갈대", "숲길", "둘레길");
            default -> List.of("설경", "온천", "박물관", "미술관", "카페");
        };
    }
    static String season(LocalDate date) { return switch (date.getMonthValue()) { case 3,4,5 -> "봄"; case 6,7,8 -> "여름"; case 9,10,11 -> "가을"; default -> "겨울"; }; }
    private static boolean seasonal(Map<String, Object> place, LocalDate date) { return seasonTerms(date).stream().anyMatch(term -> combined(place).contains(SearchCatalog.fold(term))); }
    static String baseScoreSql(Profile profile, LocalDate day, List<Object> args) {
        int seasonIndex = (day.getMonthValue()%12)/3;
        seasonIndex = Math.floorMod(seasonIndex-1,4);
        String score = "COALESCE(p.discovery_base,0)+CASE WHEN " + bitSql("p.discovery_seasons",seasonIndex) + " THEN 6 ELSE 0 END";
        var interests = new ArrayList<String>();
        for (int i=0;i<THEMES.size();i++) if (profile.interests().contains(THEMES.get(i).id())) interests.add(bitSql("p.discovery_interests",i));
        if (!interests.isEmpty()) score += "+CASE WHEN (" + String.join(" OR ", interests) + ") THEN 28 ELSE 0 END";
        if (!profile.regions().isEmpty()) {
            var areas = new ArrayList<String>();
            for (String region : profile.regions().stream().sorted().toList()) { areas.add("p.region_text LIKE ?"); args.add("%" + SearchCatalog.fold(region) + "%"); }
            score += "+CASE WHEN (" + String.join(" OR ", areas) + ") THEN 22 ELSE 0 END";
        }
        return "(" + score + ")";
    }
    static String searchOrder(String query, int topic, Profile profile, LocalDate day, List<Object> args) {
        String quality = "COALESCE(p.place_quality,2),";
        String order = quality;
        if (!query.isBlank()) {
            String fold = SearchCatalog.fold(query), escaped = fold.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            args.add(fold);
            if (topic >= 0) order = "CASE WHEN p.name_fold=? THEN 0 ELSE 1 END," + quality + "CASE WHEN " + bitSql("p.discovery_topics",topic) + " THEN 0 ELSE 1 END,";
            else {
                args.add(escaped + "%"); args.add("%" + escaped + "%");
                order = "CASE WHEN p.name_fold=? THEN 0 ELSE 1 END," + quality + "CASE WHEN p.name_fold LIKE ? ESCAPE '!' THEN 0 WHEN p.name_fold LIKE ? ESCAPE '!' THEN 1 ELSE 2 END,";
            }
        }
        return order + baseScoreSql(profile, day, args) + " DESC,p.name_fold,p.id";
    }
    private static boolean currentForecast(Map<String, Object> place, LocalDate day) {
        if (!"forecast".equals(place.get("crowdBasis")) || !day.toString().equals(place.get("crowdDate")) || !(place.get("crowdValue") instanceof Number value)) return false;
        return !CrowdForecast.label(value.doubleValue()).isEmpty();
    }
    static double evidenceScore(Map<String, Object> place, Profile profile, LocalDate day) {
        if (!currentForecast(place, day)) return 0;
        double value = ((Number) place.get("crowdValue")).doubleValue();
        if (profile.crowd().equals("popular")) return value >= 70 ? 8 : value >= 40 ? 10 : 8;
        boolean quiet = profile.crowd().equals("quiet") || profile.interests().contains("quiet");
        return value < 40 ? quiet ? 32 : 22 : value < 70 ? 4 : -30;
    }
    private static boolean duplicate(Map<String, Object> a, Map<String, Object> b) {
        if (id(a) == id(b)) return true;
        return SearchCatalog.fold(text(a, "name")).equals(SearchCatalog.fold(text(b, "name")))
                && (SearchCatalog.fold(text(a, "address")).equals(SearchCatalog.fold(text(b, "address"))) || distance(a, b) < .3);
    }
    static double distance(Map<String, Object> a, Map<String, Object> b) {
        try {
            double y1 = Double.parseDouble(text(a, "mapy")), y2 = Double.parseDouble(text(b, "mapy")), x1 = Double.parseDouble(text(a, "mapx")), x2 = Double.parseDouble(text(b, "mapx"));
            if (!(y1 > 32 && y1 < 39 && y2 > 32 && y2 < 39 && x1 > 124 && x1 < 132 && x2 > 124 && x2 < 132)) return Double.POSITIVE_INFINITY;
            double h = Math.pow(Math.sin(Math.toRadians(y2-y1)/2),2) + Math.cos(Math.toRadians(y1))*Math.cos(Math.toRadians(y2))*Math.pow(Math.sin(Math.toRadians(x2-x1)/2),2);
            return 6371*2*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
        } catch (NumberFormatException ignored) { return Double.POSITIVE_INFINITY; }
    }
    static List<Map<String, Object>> select(List<Map<String, Object>> candidates, Profile profile, LocalDate day, int limit, Map<String, Object> origin) {
        var selected = new ArrayList<Map<String, Object>>();
        var remaining = new ArrayList<>(candidates.stream().filter(DiscoveryRecommendations::eligible).toList());
        var themesById = new HashMap<Long,String>();
        candidates.forEach(place -> themesById.put(id(place),theme(place).id()));
        String originTheme = origin == null ? "" : theme(origin).id();
        if (origin != null) remaining.removeIf(p -> duplicate(p, origin) || distance(p, origin) > 30);
        while (selected.size() < limit && !remaining.isEmpty()) {
            Map<String, Object> best = null; double highest = -Double.MAX_VALUE;
            for (var place : remaining) {
                if (selected.stream().anyMatch(old -> duplicate(old, place))) continue;
                double score = ((Number) place.getOrDefault("discoveryPriority", 0)).doubleValue() + evidenceScore(place, profile, day);
                long regions = selected.stream().filter(old -> text(old,"region").equals(text(place,"region"))).count();
                long themes = selected.stream().filter(old -> themesById.get(id(old)).equals(themesById.get(id(place)))).count();
                score -= themes * 24;
                if (origin == null) { score -= regions * 35; if (profile.saved().contains(id(place))) score -= 32; }
                else { score -= distance(place, origin) * 2; if (originTheme.equals(themesById.get(id(place)))) score -= 5; }
                if (score > highest) { highest = score; best = place; }
            }
            if (best == null) break;
            selected.add(best); remaining.remove(best);
        }
        return selected;
    }
    private static boolean eligible(Map<String,Object> place) {
        var row = new LinkedHashMap<>(place);
        row.put("tour_content_type",text(place,"contentTypeId"));
        return !"course".equals(place.get("contentKind")) && PlaceContent.discoveryEligible(row);
    }
    void explain(Map<String, Object> place, Profile profile, LocalDate day) {
        if ("course".equals(place.get("contentKind"))) {
            place.put("recommendationSignals",List.of()); place.put("recommendationHint", "");
            place.put("recommendationReason", "한국관광공사에 등록된 여행 코스예요. 개별 장소의 위치와 운영정보는 코스 소개에서 확인해주세요.");
            place.remove("discoveryPriority"); return;
        }
        Theme theme = theme(place);
        var labels = new ArrayList<String>();
        if (profile.regions().contains(TourCatalog.regionName(text(place,"region")))) labels.add("관심 지역");
        THEMES.stream().filter(item -> profile.interests().contains(item.id()) && matches(item,place)).findFirst().ifPresent(item -> labels.add(item.label() + " 취향"));
        if (currentForecast(place,day) && CrowdForecast.label(((Number)place.get("crowdValue")).doubleValue()).equals("한적함")) labels.add("오늘 한적함 예측");
        if (seasonal(place,day)) labels.add(season(day) + " 여행");
        if (labels.isEmpty()) labels.add(theme.label() + " 탐방");
        place.put("recommendationHint", String.join(" · ", labels.stream().limit(2).toList()));
        place.put("recommendationSignals", labels.stream().limit(2).toList());
        String reason = (text(place,"region").isBlank() ? "" : text(place,"region") + "의 ") + text(place,"name") + ". " + theme.description();
        if (place.get("distanceKm") instanceof Number distance && Double.isFinite(distance.doubleValue()) && distance.doubleValue() >= 0)
            reason += " 지금 보고 있는 장소에서 직선거리 약 " + String.format(Locale.ROOT,"%.1f",distance.doubleValue()) + "km예요.";
        if (profile.regions().contains(TourCatalog.regionName(text(place,"region")))) reason += " 선택한 관심 지역에 있어요.";
        if (labels.stream().anyMatch(label -> label.endsWith(" 취향"))) reason += " 저장한 여행 취향과도 맞아요.";
        if (currentForecast(place,day)) reason += " 오늘의 관광공사 집중률을 환산하면 '" + CrowdForecast.label(((Number)place.get("crowdValue")).doubleValue()) + "' 예측이에요. 실제 현장과 다를 수 있어요.";
        else if (Boolean.TRUE.equals(place.get("crowdAvailable"))) reason += " 보관 자료의 혼잡도는 '" + text(place,"crowd") + "'이며, 현재 상황을 뜻하지는 않아요.";
        else reason += " 오늘의 혼잡도 자료는 아직 확인되지 않았어요.";
        place.put("recommendationReason", reason); place.put("recommendationMethod", METHOD);
        place.remove("discoveryPriority");
    }
    private Map<String, Object> candidate(Map<String, Object> row, Function<Map<String,Object>,Map<String,Object>> map) {
        var place = map.apply(row); place.put("discoveryPriority", row.getOrDefault("discovery_priority", 0)); return place;
    }
    private List<Map<String,Object>> candidates(Profile profile, LocalDate day) {
        var key = new PoolKey(profile.interests(),profile.regions(),day);
        synchronized (poolLocks[Math.floorMod(key.hashCode(),poolLocks.length)]) {
            return candidatesLocked(profile,day,key);
        }
    }
    private List<Map<String,Object>> candidatesLocked(Profile profile, LocalDate day, PoolKey key) {
        Pool pool;
        synchronized (pools) { pool = pools.get(key); }
        if (pool != null && clock.millis()-pool.createdAt()<60000 && clock.millis()>=pool.createdAt()) {
            var visible = new HashMap<Long,Map<String,Object>>();
            queries.publicDiscoveryRows(pool.choices().stream().map(Choice::id).toList()).stream().filter(row -> Boolean.TRUE.equals(row.get("discovery_eligible"))).forEach(row -> visible.put(id(row),row));
            var rows = new ArrayList<Map<String,Object>>();
            for (var choice : pool.choices()) if (visible.containsKey(choice.id())) {
                var row = new LinkedHashMap<>(visible.get(choice.id())); row.put("discovery_priority",choice.priority()); rows.add(row);
            }
            // Cached IDs are not publication authority. Refill immediately if removals empty the home feed.
            if (rows.size() >= Math.min(4,pool.choices().size())) return rows;
        }
        var rows = queries.discoveryCandidates(profile,day);
        var choices = rows.stream().map(row -> new Choice(id(row),((Number)row.get("discovery_priority")).doubleValue())).toList();
        synchronized (pools) {
            pools.put(key,new Pool(clock.millis(),choices));
            while (pools.size()>16) pools.remove(pools.keySet().iterator().next());
        }
        return rows;
    }
    Map<String,Object> home(Profile profile, Function<Map<String,Object>,Map<String,Object>> map) {
        LocalDate day = today();
        var candidates = candidates(profile,day).stream().map(row -> candidate(row,map)).toList();
        var selected = select(candidates,profile,day,4,null);
        selected.forEach(place -> explain(place,profile,day));
        var banners = new ArrayList<Map<String,Object>>();
        var heroCandidates = candidates.stream().filter(DiscoveryRecommendations::eligible).sorted(Comparator.comparingInt(place -> selected.stream().anyMatch(item -> duplicate(item,place)) ? 1 : 0)).toList();
        for (var row : heroCandidates) {
            if (!Boolean.TRUE.equals(row.get("imageVerified")) || banners.stream().anyMatch(old -> duplicate(old,row) || theme(old).id().equals(theme(row).id()))) continue;
            var banner = new LinkedHashMap<>(row); banner.remove("discoveryPriority");
            banner.put("bannerTheme",theme(row).label()); banners.add(banner); if (banners.size()==3) break;
        }
        return Map.of("items",selected,"banners",banners,"season",season(day),"date",day.toString(),"personalized",profile.personalized(),"method",METHOD);
    }
    List<Map<String,Object>> nearby(Map<String,Object> origin, Profile profile, Function<Map<String,Object>,Map<String,Object>> map) {
        if ("course".equals(origin.get("contentKind")) || !Double.isFinite(distance(origin,origin))) return List.of();
        var rows = queries.nearby(Double.parseDouble(text(origin,"mapx")),Double.parseDouble(text(origin,"mapy")),30000,"","near",1,60,true).rows();
        var candidates = rows.stream().map(row -> {
            var place = map.apply(row);
            // The bounded nearby pool is scored with the same preference/theme policy as search.
            place.put("discoveryPriority", localPriority(place,profile,today()));
            place.put("distanceKm",Math.round(((Number)row.get("distance_meters")).doubleValue()/100)/10.0); return place;
        }).toList();
        var chosen = select(candidates,profile,today(),2,origin); chosen.forEach(place -> explain(place,profile,today())); return chosen;
    }
    static double localPriority(Map<String,Object> place, Profile profile, LocalDate day) {
        String category = text(place,"category");
        double score = Set.of("walk","nature").contains(category) ? 14 : Set.of("camping","culture").contains(category) ? 8 : 0;
        if (List.of("숲길","둘레길","습지","생태","정원","계곡","해변","등대","고택").stream().anyMatch(term -> combined(place).contains(SearchCatalog.fold(term)))) score+=12;
        if (Boolean.TRUE.equals(place.get("imageVerified"))) score+=2;
        if (!text(place,"address").isBlank()) score+=2;
        if (seasonal(place,day)) score+=6;
        if (THEMES.stream().anyMatch(item -> profile.interests().contains(item.id()) && matches(item,place))) score+=28;
        if (profile.regions().contains(TourCatalog.regionName(text(place,"region")))) score+=22;
        return score;
    }
}
