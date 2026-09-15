package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProgressService {
    record Badge(String id, String label, String activity, int target, String grade, String color,
                 int reward, String group, String condition) {}
    private final List<Badge> definitions;
    private final JdbcTemplate jdbc;

    public ProgressService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        try (var source = getClass().getResourceAsStream("/data/badges.json")) {
            if (source == null) throw new IllegalStateException("Badge data is missing");
            definitions = mapper.readValue(source, new TypeReference<>() {});
        } catch (java.io.IOException error) { throw new IllegalStateException("Badge data could not be read", error); }
        if (definitions.stream().map(Badge::id).distinct().count() != definitions.size()
                || definitions.stream().anyMatch(badge -> badge.target < 1 || badge.reward < 0)) {
            throw new IllegalStateException("Invalid badge definitions");
        }
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_activity_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(255) NOT NULL, activity_type VARCHAR(40) NOT NULL, target_id VARCHAR(255) NOT NULL, exp_amount INT NOT NULL, created_at TIMESTAMP, UNIQUE(user_id, activity_type, target_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_badges (user_id VARCHAR(255) NOT NULL, badge_id VARCHAR(80) NOT NULL, earned_at TIMESTAMP, PRIMARY KEY(user_id, badge_id))");
    }

    @Transactional
    public void record(String user, String type, String target) {
        if (target == null || target.isBlank() || definitions.stream().noneMatch(badge -> badge.activity.equals(type))) return;
        // Serialize rewards per account so concurrent requests cannot grant duplicate EXP.
        jdbc.queryForList("SELECT username FROM user_accounts WHERE username=? FOR UPDATE", user);
        long existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_activity_logs WHERE user_id=? AND activity_type=? AND target_id=?", Long.class, user, type, target);
        if (existing == 0) {
            int exp = switch (type) { case "view" -> 2; case "favorite" -> 5; case "review" -> 20; case "diary", "submission" -> 30; default -> 0; };
            jdbc.update("INSERT INTO user_activity_logs(user_id,activity_type,target_id,exp_amount,created_at) VALUES(?,?,?,?,?)", user, type, target, exp, LocalDateTime.now());
        }
        awardEligible(user, activityCounts(user));
    }

    private Map<String, Long> activityCounts(String user) {
        var counts = new LinkedHashMap<String, Long>();
        jdbc.queryForList("SELECT activity_type, COUNT(*) AS total FROM user_activity_logs WHERE user_id=? GROUP BY activity_type", user)
                .forEach(row -> counts.put(String.valueOf(row.get("activity_type")), ((Number) row.get("total")).longValue()));
        return counts;
    }

    private void awardEligible(String user, Map<String, Long> counts) {
        var earned = earnedIds(user);
        for (Badge badge : definitions) {
            if (counts.getOrDefault(badge.activity, 0L) < badge.target || earned.contains(badge.id)) continue;
            jdbc.update("INSERT INTO user_badges(user_id,badge_id,earned_at) VALUES(?,?,?)", user, badge.id, LocalDateTime.now());
            jdbc.update("INSERT INTO user_activity_logs(user_id,activity_type,target_id,exp_amount,created_at) VALUES(?,?,?,?,?)", user, "badge", badge.id, badge.reward, LocalDateTime.now());
            jdbc.update("INSERT INTO notifications(user_id,type,title,message,reference_type,reference_id,read_flag,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    user, "badge", "새로운 알림이 도착했어요!", badge.label + " 배지를 획득했어요.", "badge", badge.id, false, LocalDateTime.now());
        }
    }

    public List<String> earnedIds(String user) {
        return jdbc.queryForList("SELECT badge_id FROM user_badges WHERE user_id=?", String.class, user);
    }

    @Transactional
    public Map<String, Object> summary(String user) {
        jdbc.queryForList("SELECT username FROM user_accounts WHERE username=? FOR UPDATE", user);
        // Reconcile older approvals and milestones without rewriting existing awards or EXP.
        jdbc.queryForList("SELECT id FROM place_submissions WHERE user_id=? AND status='승인'", Long.class, user).forEach(id -> {
            long exists = jdbc.queryForObject("SELECT COUNT(*) FROM user_activity_logs WHERE user_id=? AND activity_type='submission-approved' AND target_id=?", Long.class, user, String.valueOf(id));
            if (exists == 0) jdbc.update("INSERT INTO user_activity_logs(user_id,activity_type,target_id,exp_amount,created_at) VALUES(?,?,?,?,?)", user, "submission-approved", String.valueOf(id), 0, LocalDateTime.now());
        });
        var counts = activityCounts(user);
        awardEligible(user, counts);
        var earned = earnedIds(user);
        var badges = definitions.stream().map(badge -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", badge.id); result.put("label", badge.label); result.put("grade", badge.grade);
            result.put("color", badge.color); result.put("target", badge.target);
            result.put("activity", badge.activity); result.put("group", badge.group);
            result.put("condition", badge.condition); result.put("reward", badge.reward);
            result.put("count", Math.min(badge.target, counts.getOrDefault(badge.activity, 0L)));
            result.put("earned", earned.contains(badge.id));
            return result;
        }).toList();
        int exp = jdbc.queryForObject("SELECT COALESCE(SUM(exp_amount),0) FROM user_activity_logs WHERE user_id=?", Integer.class, user);
        int[] thresholds = {0, 100, 300, 600, 1000};
        String[] names = {"새싹 여행자", "동네 탐험가", "숨은길 발견자", "지역 탐험가", "숨길 마스터"};
        int level = 0;
        while (level < 4 && exp >= thresholds[level + 1]) level++;
        return Map.of("badges", badges, "exp", exp, "level", level + 1, "levelName", names[level],
                "currentLevelExp", thresholds[level], "nextLevelExp", level == 4 ? thresholds[level] : thresholds[level + 1]);
    }
}
