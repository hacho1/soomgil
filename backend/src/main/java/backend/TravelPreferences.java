package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TravelPreferences {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String, String> interestIds = new LinkedHashMap<>();
    private Set<String> regions;

    public TravelPreferences(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @PostConstruct
    void initialize() throws Exception {
        try (var stream = new ClassPathResource("data/travel-preferences.json").getInputStream()) {
            for (var choice : mapper.readTree(stream)) {
                interestIds.put(choice.path("id").asText(), choice.path("id").asText());
                interestIds.put(choice.path("label").asText(), choice.path("id").asText());
            }
        }
        try (var stream = new ClassPathResource("data/regions.json").getInputStream()) {
            regions = new LinkedHashSet<>(mapper.readValue(stream, new TypeReference<List<String>>() {}));
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_preferences (
                    user_id VARCHAR(255) PRIMARY KEY,
                    crowd_preference VARCHAR(20) NOT NULL,
                    preferred_regions_json VARCHAR(2000) NOT NULL,
                    onboarding_completed BOOLEAN NOT NULL,
                    onboarding_step INT NOT NULL
                )
                """);
    }

    public void createForNewUser(String userId) {
        jdbc.update("INSERT INTO user_preferences VALUES(?,'normal','[]',FALSE,0)", userId);
    }

    public Map<String, Object> read(String userId) {
        var rows = jdbc.queryForList("SELECT * FROM user_preferences WHERE user_id=?", userId);
        // Existing accounts have no onboarding row and keep access to their original screens.
        if (rows.isEmpty()) return Map.of("crowdPreference", "normal", "preferredRegions", List.of(), "onboardingCompleted", true, "onboardingStep", 3);
        var row = rows.get(0);
        return Map.of("crowdPreference", row.get("crowd_preference"), "preferredRegions", decode(row.get("preferred_regions_json")),
                "onboardingCompleted", row.get("onboarding_completed"), "onboardingStep", row.get("onboarding_step"));
    }

    public List<String> normalizeInterests(Object value) {
        var list = stringList(value, 17);
        var result = new LinkedHashSet<String>();
        for (String item : list) {
            String id = interestIds.get(item);
            if (id == null) throw invalid("관심 여행 종류를 다시 선택해주세요.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    public String interestsJson(Object value) {
        return encode(normalizeInterests(value instanceof String ? decode(value) : value));
    }

    @Transactional
    public void save(String userId, Map<String, Object> body) {
        var interests = normalizeInterests(body.get("interests"));
        String crowd = body.get("crowdPreference") instanceof String text ? text : "";
        if (!Set.of("quiet", "normal", "popular").contains(crowd)) throw invalid("혼잡도 선호를 선택해주세요.");
        var selectedRegions = stringList(body.get("preferredRegions"), regions.size());
        if (!regions.containsAll(selectedRegions)) throw invalid("관심 지역을 다시 선택해주세요.");
        if (!(body.get("onboardingStep") instanceof Number step) || step.doubleValue() != step.intValue() || step.intValue() < 0 || step.intValue() > 3) throw invalid("설정 단계를 확인해주세요.");
        if (!(body.get("onboardingCompleted") instanceof Boolean completed)) throw invalid("완료 상태를 확인해주세요.");
        if (completed && step.intValue() != 3) throw invalid("여행 취향 설정을 마쳐주세요.");
        jdbc.queryForList("SELECT user_id FROM user_settings WHERE user_id=? FOR UPDATE", userId);
        var old = read(userId);
        boolean exists = jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_id=?", Long.class, userId) > 0;
        boolean done = completed || Boolean.TRUE.equals(old.get("onboardingCompleted"));
        if (!exists) jdbc.update("INSERT INTO user_preferences VALUES(?,?,?,?,?)", userId, crowd, encode(selectedRegions), done, step.intValue());
        else jdbc.update("UPDATE user_preferences SET crowd_preference=?,preferred_regions_json=?,onboarding_completed=?,onboarding_step=? WHERE user_id=?", crowd, encode(selectedRegions), done, step.intValue(), userId);
        jdbc.update("UPDATE user_settings SET interests_json=? WHERE user_id=?", encode(interests), userId);
    }

    private List<String> stringList(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.size() > limit || list.stream().anyMatch(item -> !(item instanceof String) || ((String) item).length() > 30)) throw invalid("선택 항목을 확인해주세요.");
        return list.stream().map(String.class::cast).distinct().toList();
    }

    private List<String> decode(Object value) {
        try { return mapper.readValue(String.valueOf(value), new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw invalid("선택 항목을 확인해주세요."); }
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Preference serialization failed", e); }
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
