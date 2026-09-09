package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@CrossOrigin(origins = {
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:4173",
        "http://127.0.0.1:4173",
        "http://iiri.hknu.ac.kr",
        "https://iiri.hknu.ac.kr",
        "http://220.68.241.128",
        "https://220.68.241.128"
})
@RestController
@RequestMapping("/api")
public class SoomgilApiController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{3,19}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$");
    private static final List<String> BANNED_WORDS = List.of(
            "씨발", "시발", "ㅅㅂ", "병신", "ㅂㅅ", "섹스", "야동", "좌빨", "수꼴"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${soomgil.admin.code:ADMIN2026}")
    private String adminCode;

    public SoomgilApiController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void initialize() {
        createSchema();
        seedPlaces();
        seedDefaultUsers();
        seedNotice();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "soomgil", "time", LocalDateTime.now().toString());
    }

    @GetMapping("/places")
    public List<Map<String, Object>> places(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String region,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "recommended") String sort
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM places");
        String q = normalize(query);
        String regionText = normalize(region);
        String categoryText = normalize(category);

        List<Map<String, Object>> result = rows.stream()
                .map(this::placeMap)
                .filter(place -> matchesQuery(place, q))
                .filter(place -> regionText.isBlank() || normalize(text(place.get("region"))).contains(regionText)
                        || normalize(text(place.get("address"))).contains(regionText))
                .filter(place -> categoryText.isBlank() || normalize(text(place.get("category"))).contains(categoryText)
                        || normalize(text(place.get("categoryLabel"))).contains(categoryText)
                        || normalize(text(place.get("tags"))).contains(categoryText))
                .sorted(placeComparator(sort))
                .toList();

        if (!q.isBlank()) {
            saveSearchLog("guest", query);
        }
        return result;
    }

    @GetMapping("/places/{id}")
    public Map<String, Object> place(@PathVariable long id) {
        Map<String, Object> row = findPlaceRow(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."));
        Map<String, Object> place = placeMap(row);
        place.put("recommendationReason", recommendationReason(place));
        place.put("halfDayCourse", halfDayCourse(place));
        place.put("nearbyHiddenPlaces", nearbyHiddenPlaces(id));
        return place;
    }

    @GetMapping("/places/{id}/reviews")
    public List<Map<String, Object>> reviews(
            @PathVariable long id,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        String order = "likes".equals(sort) ? "like_count DESC, created_at DESC" : "created_at DESC";
        return jdbc.queryForList("SELECT * FROM reviews WHERE place_id=? ORDER BY " + order, id).stream()
                .map(this::reviewMap)
                .toList();
    }

    @PostMapping("/places/{id}/reviews")
    public Map<String, Object> createReview(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String userId = defaultUser(body.get("userId"));
        String text = requireText(body.get("text"), "후기 내용을 입력해주세요.");
        rejectBannedText(text);
        ensurePlace(id);
        jdbc.update(
                "INSERT INTO reviews(created_at,rating,text,user_id,place_id,like_count,liked_by_json) VALUES(?,?,?,?,?,?,?)",
                LocalDateTime.now(), 0, text, userId, id, 0, "[]"
        );
        return Map.of("ok", true, "reviews", reviews(id, "latest"));
    }

    @DeleteMapping("/reviews/{id}")
    public Map<String, Object> deleteReview(@PathVariable long id, @RequestParam String userId) {
        if ("admin".equals(userId)) {
            jdbc.update("DELETE FROM reviews WHERE id=?", id);
        } else {
            jdbc.update("DELETE FROM reviews WHERE id=? AND user_id=?", id, userId);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/reviews/{id}/like")
    public Map<String, Object> likeReview(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String userId = defaultUser(body.get("userId"));
        Map<String, Object> review = jdbc.queryForMap("SELECT * FROM reviews WHERE id=?", id);
        Set<String> likedBy = new LinkedHashSet<>(readStringList(text(value(review, "liked_by_json"))));
        boolean liked = likedBy.contains(userId);
        if (liked) {
            likedBy.remove(userId);
        } else {
            likedBy.add(userId);
            String owner = text(value(review, "user_id"));
            if (!owner.equals(userId)) {
                createNotification(owner, "review-like", "후기에 공감이 추가됐어요", "익명의 사용자가 회원님의 후기에 좋아요를 눌렀습니다.", "review", String.valueOf(id));
            }
        }
        jdbc.update("UPDATE reviews SET like_count=?, liked_by_json=? WHERE id=?", likedBy.size(), writeJson(likedBy), id);
        return Map.of("ok", true, "liked", !liked, "likeCount", likedBy.size());
    }

    @GetMapping("/auth/users/exists")
    public Map<String, Object> userExists(
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String phone
    ) {
        return Map.of(
                "usernameExists", !username.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE username=?", username) > 0,
                "emailExists", !email.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE email=?", email) > 0,
                "phoneExists", !phone.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE phone=?", phone) > 0
        );
    }

    @PostMapping("/auth/signup")
    public Map<String, Object> signup(@RequestBody Map<String, Object> body) {
        String fullName = requireText(body.get("fullName"), "성명을 입력해주세요.");
        String username = requireText(body.get("username"), "아이디를 입력해주세요.").toLowerCase(Locale.ROOT);
        String password = requireText(body.get("password"), "비밀번호를 입력해주세요.");
        String email = requireText(body.get("email"), "이메일을 입력해주세요.").toLowerCase(Locale.ROOT);
        String phone = requireText(body.get("phone"), "전화번호를 입력해주세요.");
        boolean consent = bool(body.get("privacyConsent"));

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw badRequest("아이디는 영문 소문자로 시작하고 영문/숫자/_ 포함 4~20자여야 합니다.");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw badRequest("이메일 형식을 확인해주세요.");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw badRequest("비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상이어야 합니다.");
        }
        if (!consent) {
            throw badRequest("개인정보보호 동의가 필요합니다.");
        }
        rejectBannedText(username);

        Map<String, Object> exists = userExists(username, email, phone);
        if (bool(exists.get("usernameExists")) || bool(exists.get("emailExists")) || bool(exists.get("phoneExists"))) {
            throw badRequest("이미 가입된 사용자입니다.");
        }

        jdbc.update("""
                INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, email, true, fullName, LocalDateTime.now(), hashPassword(password), phone, true, consent, username, "local", username);
        upsertUserSetting(username, fullName, email, phone);
        createNotification(username, "welcome", "새로운 알림이 도착했어요!", "숨길 가입이 완료되었습니다.", "user", username);
        return Map.of("ok", true, "user", userResponse(username));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String identifier = requireText(body.get("identifier"), "아이디 또는 이메일을 입력해주세요.").toLowerCase(Locale.ROOT);
        String password = requireText(body.get("password"), "비밀번호를 입력해주세요.");
        if (!USERNAME_PATTERN.matcher(identifier).matches() && !EMAIL_PATTERN.matcher(identifier).matches()) {
            throw badRequest("아이디 또는 이메일 형식을 확인해주세요.");
        }

        Map<String, Object> account = findAccount(identifier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "가입된 사용자를 찾을 수 없습니다."));
        String storedHash = text(value(account, "password_hash"));
        if (!verifyPassword(password, storedHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다.");
        }
        String username = text(value(account, "username"));
        jdbc.update("UPDATE user_settings SET logged_in=? WHERE user_id=?", true, username);
        return Map.of("ok", true, "user", userResponse(username));
    }

    @PostMapping("/auth/google")
    public Map<String, Object> googleLogin(@RequestBody Map<String, Object> body) {
        String email = requireText(body.get("email"), "구글 이메일을 확인할 수 없습니다.").toLowerCase(Locale.ROOT);
        String fullName = textOr(body.get("fullName"), email.substring(0, email.indexOf("@")));
        String providerId = textOr(body.get("providerId"), email);
        String username = normalizeUsername(textOr(body.get("username"), email.substring(0, email.indexOf("@"))));
        int suffix = 1;
        String base = username;
        while (count("SELECT COUNT(*) FROM user_accounts WHERE username=? AND email<>?", username, email) > 0) {
            username = base + suffix++;
        }

        Optional<Map<String, Object>> account = findAccount(email);
        if (account.isEmpty()) {
            jdbc.update("""
                    INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, email, true, fullName, LocalDateTime.now(), "oauth$google$" + providerId, "google:" + providerId, false, true, username, "google", providerId);
            upsertUserSetting(username, fullName, email, "");
        } else {
            username = text(value(account.get(), "username"));
        }
        return Map.of("ok", true, "user", userResponse(username));
    }

    @PostMapping("/auth/find-id")
    public Map<String, Object> findId(@RequestBody Map<String, Object> body) {
        String email = text(body.get("email")).toLowerCase(Locale.ROOT);
        String phone = text(body.get("phone"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT username FROM user_accounts WHERE email=? OR phone=?", email, phone);
        return Map.of("ok", true, "usernames", rows.stream().map(row -> text(value(row, "username"))).toList());
    }

    @PostMapping("/auth/reset-password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, Object> body) {
        String identifier = requireText(body.get("identifier"), "아이디 또는 이메일을 입력해주세요.").toLowerCase(Locale.ROOT);
        String newPassword = requireText(body.get("newPassword"), "새 비밀번호를 입력해주세요.");
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw badRequest("비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상이어야 합니다.");
        }
        Map<String, Object> account = findAccount(identifier).orElseThrow(() -> badRequest("사용자를 찾을 수 없습니다."));
        jdbc.update("UPDATE user_accounts SET password_hash=? WHERE username=?",
                hashPassword(newPassword), text(value(account, "username")));
        return Map.of("ok", true, "message", "비밀번호가 변경되었습니다.");
    }

    @GetMapping("/users/{userId}/settings")
    public Map<String, Object> settings(@PathVariable String userId) {
        ensureUserSetting(userId);
        return userResponse(userId);
    }

    @PutMapping("/users/{userId}/settings")
    public Map<String, Object> updateSettings(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        ensureUserSetting(userId);
        String[] columns = {"nickname", "profile_image", "profile_border", "interests_json", "display_badge_ids_json",
                "dark_mode", "email", "phone", "address_sido", "address_sigungu", "address_eupmyeondong", "notification_enabled"};
        for (String column : columns) {
            String camel = toCamel(column);
            if (body.containsKey(camel) || body.containsKey(column)) {
                Object value = body.containsKey(camel) ? body.get(camel) : body.get(column);
                if ("nickname".equals(column)) {
                    rejectBannedText(text(value));
                }
                jdbc.update("UPDATE user_settings SET " + column + "=? WHERE user_id=?", value, userId);
            }
        }
        return Map.of("ok", true, "user", userResponse(userId));
    }

    @GetMapping("/users/{userId}/favorites")
    public List<Map<String, Object>> favorites(@PathVariable String userId) {
        return jdbc.queryForList("""
                SELECT p.* FROM favorites f
                JOIN places p ON p.id=f.place_id
                WHERE f.user_id=?
                ORDER BY f.created_at DESC
                """, userId).stream().map(this::placeMap).toList();
    }

    @PostMapping("/users/{userId}/favorites")
    public Map<String, Object> addFavorite(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        long placeId = number(body.get("placeId"));
        ensurePlace(placeId);
        if (count("SELECT COUNT(*) FROM favorites WHERE user_id=? AND place_id=?", userId, placeId) == 0) {
            jdbc.update("INSERT INTO favorites(user_id,created_at,place_id) VALUES(?,?,?)", userId, LocalDateTime.now(), placeId);
        }
        return Map.of("ok", true, "favorites", favorites(userId));
    }

    @DeleteMapping("/users/{userId}/favorites/{placeId}")
    public Map<String, Object> deleteFavorite(@PathVariable String userId, @PathVariable long placeId) {
        jdbc.update("DELETE FROM favorites WHERE user_id=? AND place_id=?", userId, placeId);
        return Map.of("ok", true);
    }

    @GetMapping("/users/{userId}/stats")
    public Map<String, Object> userStats(@PathVariable String userId) {
        return Map.of(
                "favorites", count("SELECT COUNT(*) FROM favorites WHERE user_id=?", userId),
                "reviews", count("SELECT COUNT(*) FROM reviews WHERE user_id=?", userId),
                "following", count("SELECT COUNT(*) FROM follows WHERE follower_id=?", userId),
                "followers", count("SELECT COUNT(*) FROM follows WHERE following_id=?", userId),
                "diaries", count("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", userId)
        );
    }

    @PostMapping("/users/{userId}/following/{targetId}")
    public Map<String, Object> follow(@PathVariable String userId, @PathVariable String targetId) {
        if (userId.equals(targetId)) {
            throw badRequest("자기 자신은 팔로우할 수 없습니다.");
        }
        ensureUserSetting(userId);
        ensureUserSetting(targetId);
        if (count("SELECT COUNT(*) FROM follows WHERE follower_id=? AND following_id=?", userId, targetId) == 0) {
            jdbc.update("INSERT INTO follows(follower_id,following_id,created_at) VALUES(?,?,?)", userId, targetId, LocalDateTime.now());
            createNotification(targetId, "follow", "새로운 알림이 도착했어요!", userId + "님이 회원님을 팔로우했습니다.", "user", userId);
        }
        return Map.of("ok", true, "stats", userStats(userId));
    }

    @DeleteMapping("/users/{userId}/following/{targetId}")
    public Map<String, Object> unfollow(@PathVariable String userId, @PathVariable String targetId) {
        jdbc.update("DELETE FROM follows WHERE follower_id=? AND following_id=?", userId, targetId);
        return Map.of("ok", true, "stats", userStats(userId));
    }

    @GetMapping("/users/{userId}/following")
    public List<Map<String, Object>> following(@PathVariable String userId) {
        return people("""
                SELECT s.* FROM follows f
                JOIN user_settings s ON s.user_id=f.following_id
                WHERE f.follower_id=?
                ORDER BY f.created_at DESC
                """, userId);
    }

    @GetMapping("/users/{userId}/followers")
    public List<Map<String, Object>> followers(@PathVariable String userId) {
        return people("""
                SELECT s.* FROM follows f
                JOIN user_settings s ON s.user_id=f.follower_id
                WHERE f.following_id=?
                ORDER BY f.created_at DESC
                """, userId);
    }

    @GetMapping("/users/{userId}/following-favorites")
    public List<Map<String, Object>> followingFavorites(@PathVariable String userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT p.* FROM follows fo
                JOIN favorites f ON f.user_id=fo.following_id
                JOIN places p ON p.id=f.place_id
                WHERE fo.follower_id=?
                ORDER BY p.name
                """, userId).stream().map(this::placeMap).toList();
    }

    @GetMapping("/users/{userId}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable String userId) {
        return jdbc.queryForList("""
                SELECT * FROM notifications
                WHERE user_id=? OR user_id='*'
                ORDER BY created_at DESC
                """, userId).stream().map(this::notificationMap).toList();
    }

    @PutMapping("/users/{userId}/notifications/read")
    public Map<String, Object> readNotifications(@PathVariable String userId) {
        jdbc.update("UPDATE notifications SET read_flag=? WHERE user_id=?", true, userId);
        return Map.of("ok", true);
    }

    @GetMapping("/notices")
    public List<Map<String, Object>> notices() {
        return jdbc.queryForList("SELECT * FROM notices ORDER BY created_at DESC").stream()
                .map(this::noticeMap)
                .toList();
    }

    @GetMapping("/notices/{id}")
    public Map<String, Object> notice(@PathVariable long id) {
        return noticeMap(jdbc.queryForMap("SELECT * FROM notices WHERE id=?", id));
    }

    @PostMapping("/admin/notices")
    public Map<String, Object> createNotice(@RequestParam String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        String title = requireText(body.get("title"), "공지 제목을 입력해주세요.");
        String bodyText = requireText(body.get("body"), "공지 내용을 입력해주세요.");
        long id = insertAndGetId("""
                INSERT INTO notices(title,body,body_size,body_weight,image_url,created_at,updated_at) VALUES(?,?,?,?,?,?,?)
                """, title, bodyText, textOr(body.get("bodySize"), "normal"), textOr(body.get("bodyWeight"), "normal"),
                text(body.get("imageUrl")), LocalDateTime.now(), LocalDateTime.now());
        createNotification("*", "notice", "새로운 알림이 도착했어요!", title, "notice", String.valueOf(id));
        return Map.of("ok", true, "notice", notice(id));
    }

    @PutMapping("/admin/notices/{id}")
    public Map<String, Object> updateNotice(@PathVariable long id, @RequestParam String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        jdbc.update("""
                UPDATE notices SET title=?, body=?, body_size=?, body_weight=?, image_url=?, updated_at=? WHERE id=?
                """, requireText(body.get("title"), "공지 제목을 입력해주세요."),
                requireText(body.get("body"), "공지 내용을 입력해주세요."),
                textOr(body.get("bodySize"), "normal"),
                textOr(body.get("bodyWeight"), "normal"),
                text(body.get("imageUrl")),
                LocalDateTime.now(),
                id);
        return Map.of("ok", true, "notice", notice(id));
    }

    @DeleteMapping("/admin/notices/{id}")
    public Map<String, Object> deleteNotice(@PathVariable long id, @RequestParam String adminCode) {
        requireAdmin(adminCode);
        jdbc.update("DELETE FROM notices WHERE id=?", id);
        return Map.of("ok", true);
    }

    @GetMapping("/users/{userId}/inquiries")
    public List<Map<String, Object>> userInquiries(@PathVariable String userId) {
        return jdbc.queryForList("SELECT * FROM inquiries WHERE user_id=? ORDER BY created_at DESC", userId).stream()
                .map(this::inquiryMap)
                .toList();
    }

    @PostMapping("/users/{userId}/inquiries")
    public Map<String, Object> createInquiry(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        long id = insertAndGetId("""
                INSERT INTO inquiries(user_id,title,body,status,created_at,updated_at) VALUES(?,?,?,?,?,?)
                """, userId, requireText(body.get("title"), "문의 제목을 입력해주세요."),
                requireText(body.get("body"), "문의 내용을 입력해주세요."),
                "대기", LocalDateTime.now(), LocalDateTime.now());
        return Map.of("ok", true, "inquiry", inquiry(id));
    }

    @PutMapping("/users/{userId}/inquiries/{id}")
    public Map<String, Object> updateInquiry(@PathVariable String userId, @PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE inquiries SET title=?, body=?, updated_at=? WHERE id=? AND user_id=? AND status<>'답변완료'",
                requireText(body.get("title"), "문의 제목을 입력해주세요."),
                requireText(body.get("body"), "문의 내용을 입력해주세요."),
                LocalDateTime.now(), id, userId);
        return Map.of("ok", true, "inquiry", inquiry(id));
    }

    @DeleteMapping("/users/{userId}/inquiries/{id}")
    public Map<String, Object> deleteInquiry(@PathVariable String userId, @PathVariable long id) {
        jdbc.update("DELETE FROM inquiries WHERE id=? AND user_id=?", id, userId);
        return Map.of("ok", true);
    }

    @GetMapping("/admin/inquiries")
    public List<Map<String, Object>> adminInquiries(@RequestParam String adminCode) {
        requireAdmin(adminCode);
        return jdbc.queryForList("SELECT * FROM inquiries ORDER BY created_at DESC").stream()
                .map(this::inquiryMap)
                .toList();
    }

    @PutMapping("/admin/inquiries/{id}/reply")
    public Map<String, Object> replyInquiry(@PathVariable long id, @RequestParam String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        String reply = requireText(body.get("reply"), "답변 내용을 입력해주세요.");
        Map<String, Object> inquiry = inquiry(id);
        jdbc.update("UPDATE inquiries SET admin_reply=?, replied_at=?, status=?, updated_at=? WHERE id=?",
                reply, LocalDateTime.now(), "답변완료", LocalDateTime.now(), id);
        createNotification(text(inquiry.get("userId")), "inquiry", "새로운 알림이 도착했어요!", "문의 답변이 등록되었습니다.", "inquiry", String.valueOf(id));
        return Map.of("ok", true, "inquiry", inquiry(id));
    }

    @DeleteMapping("/admin/inquiries/{id}")
    public Map<String, Object> deleteInquiryAdmin(@PathVariable long id, @RequestParam String adminCode) {
        requireAdmin(adminCode);
        jdbc.update("DELETE FROM inquiries WHERE id=?", id);
        return Map.of("ok", true);
    }

    @PostMapping("/place-submissions")
    public Map<String, Object> createPlaceSubmission(@RequestBody Map<String, Object> body) {
        String userId = defaultUser(body.get("userId"));
        long id = insertAndGetId("""
                INSERT INTO place_submissions(user_id,name,address,region,category,category_label,description,status,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, userId, requireText(body.get("name"), "장소 이름을 입력해주세요."),
                requireText(body.get("address"), "주소를 입력해주세요."),
                text(body.get("region")),
                textOr(body.get("category"), "tour"),
                textOr(body.get("categoryLabel"), "관광지"),
                text(body.get("description")),
                "대기",
                LocalDateTime.now());
        return Map.of("ok", true, "submission", submission(id));
    }

    @GetMapping("/admin/place-submissions")
    public List<Map<String, Object>> placeSubmissions(@RequestParam String adminCode) {
        requireAdmin(adminCode);
        return jdbc.queryForList("SELECT * FROM place_submissions ORDER BY created_at DESC").stream()
                .map(this::submissionMap)
                .toList();
    }

    @PutMapping("/admin/place-submissions/{id}")
    public Map<String, Object> updatePlaceSubmission(@PathVariable long id, @RequestParam String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        if (body.containsKey("name") || body.containsKey("address") || body.containsKey("description")) {
            jdbc.update("""
                    UPDATE place_submissions SET name=?, address=?, region=?, category=?, category_label=?, description=? WHERE id=?
                    """, textOr(body.get("name"), text(submission(id).get("name"))),
                    textOr(body.get("address"), text(submission(id).get("address"))),
                    text(body.get("region")),
                    textOr(body.get("category"), "tour"),
                    textOr(body.get("categoryLabel"), "관광지"),
                    text(body.get("description")),
                    id);
        }
        if (body.containsKey("status")) {
            String status = text(body.get("status"));
            jdbc.update("UPDATE place_submissions SET status=?, reviewed_at=? WHERE id=?", status, LocalDateTime.now(), id);
            Map<String, Object> sub = submission(id);
            String userId = text(sub.get("userId"));
            createNotification(userId, "place-submission", "새로운 알림이 도착했어요!", "제보한 장소가 " + status + " 처리되었습니다.", "place-submission", String.valueOf(id));
            if ("승인".equals(status)) {
                createPlaceFromSubmission(sub);
            }
        }
        return Map.of("ok", true, "submission", submission(id));
    }

    @GetMapping("/users/{userId}/diaries")
    public List<Map<String, Object>> diaries(@PathVariable String userId) {
        return jdbc.queryForList("SELECT * FROM diary_entries WHERE user_id=? ORDER BY visited_date DESC, created_at DESC", userId).stream()
                .map(this::diaryMap)
                .toList();
    }

    @PostMapping("/users/{userId}/diaries")
    public Map<String, Object> createDiary(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        long id = insertAndGetId("""
                INSERT INTO diary_entries(user_id,title,body,place_name,visited_date,weather_icon,page_color,font_family,image_url,drawing_image,drawing_layout,badge_stickers,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, userId, requireText(body.get("title"), "제목을 입력해주세요."),
                text(body.get("body")),
                text(body.get("placeName")),
                LocalDate.parse(textOr(body.get("visitedDate"), LocalDate.now().toString())),
                textOr(body.get("weatherIcon"), "sun"),
                textOr(body.get("pageColor"), "#ffffff"),
                textOr(body.get("fontFamily"), "Pretendard"),
                text(body.get("imageUrl")),
                text(body.get("drawingImage")),
                textOr(body.get("drawingLayout"), "inline"),
                textOr(body.get("badgeStickers"), "[]"),
                LocalDateTime.now(),
                LocalDateTime.now());
        return Map.of("ok", true, "diary", diary(id));
    }

    @PutMapping("/users/{userId}/diaries/{id}")
    public Map<String, Object> updateDiary(@PathVariable String userId, @PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                UPDATE diary_entries SET title=?, body=?, place_name=?, visited_date=?, weather_icon=?, page_color=?, font_family=?, image_url=?, drawing_image=?, drawing_layout=?, badge_stickers=?, updated_at=?
                WHERE id=? AND user_id=?
                """, requireText(body.get("title"), "제목을 입력해주세요."),
                text(body.get("body")),
                text(body.get("placeName")),
                LocalDate.parse(textOr(body.get("visitedDate"), LocalDate.now().toString())),
                textOr(body.get("weatherIcon"), "sun"),
                textOr(body.get("pageColor"), "#ffffff"),
                textOr(body.get("fontFamily"), "Pretendard"),
                text(body.get("imageUrl")),
                text(body.get("drawingImage")),
                textOr(body.get("drawingLayout"), "inline"),
                textOr(body.get("badgeStickers"), "[]"),
                LocalDateTime.now(),
                id,
                userId);
        return Map.of("ok", true, "diary", diary(id));
    }

    @DeleteMapping("/users/{userId}/diaries/{id}")
    public Map<String, Object> deleteDiary(@PathVariable String userId, @PathVariable long id) {
        jdbc.update("DELETE FROM diary_entries WHERE id=? AND user_id=?", id, userId);
        return Map.of("ok", true);
    }

    @GetMapping("/suggestions")
    public List<String> suggestions(@RequestParam(defaultValue = "") String q) {
        String keyword = normalize(q);
        List<String> base = List.of(
                "바다", "해변", "해수욕장", "계곡", "캠핑", "글램핑", "차박", "산책길", "둘레길", "숲길",
                "카페", "감성카페", "맛집", "전망대", "야경", "역사", "전시", "반려동물", "무장애", "아이와 갈만한 곳",
                "강릉 바다", "제주 오름", "서울 야경", "부산 해수욕장", "경기 계곡", "전국 숨은 장소"
        );
        if (keyword.isBlank()) {
            return base.stream().limit(10).toList();
        }
        return base.stream()
                .filter(item -> normalize(item).contains(keyword) || matchesAlias(item, keyword))
                .limit(12)
                .toList();
    }

    private void createSchema() {
        execute("""
                CREATE TABLE IF NOT EXISTS user_accounts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    email VARCHAR(255) UNIQUE NOT NULL,
                    email_verified BOOLEAN,
                    full_name VARCHAR(255) NOT NULL,
                    joined_at TIMESTAMP,
                    password_hash VARCHAR(255) NOT NULL,
                    phone VARCHAR(255) UNIQUE NOT NULL,
                    phone_verified BOOLEAN,
                    privacy_consent BOOLEAN,
                    username VARCHAR(255) UNIQUE NOT NULL,
                    auth_provider VARCHAR(255),
                    provider_id VARCHAR(255)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS user_settings (
                    user_id VARCHAR(255) PRIMARY KEY,
                    interests_json VARCHAR(4000),
                    dark_mode BOOLEAN,
                    share_count INT,
                    email VARCHAR(255),
                    joined_at TIMESTAMP,
                    nickname VARCHAR(255),
                    phone VARCHAR(255),
                    phone_verified BOOLEAN,
                    privacy_consent BOOLEAN,
                    profile_border VARCHAR(255),
                    profile_image LONGTEXT,
                    email_verified BOOLEAN,
                    full_name VARCHAR(255),
                    logged_in BOOLEAN,
                    username VARCHAR(255),
                    address_sido VARCHAR(255),
                    address_sigungu VARCHAR(255),
                    address_eupmyeondong VARCHAR(255),
                    display_badge_ids_json VARCHAR(1000),
                    notification_enabled BOOLEAN
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS places (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    address VARCHAR(600) NOT NULL,
                    category VARCHAR(80),
                    category_label VARCHAR(120),
                    concentration VARCHAR(80),
                    hidden_score VARCHAR(80),
                    image VARCHAR(1000),
                    level VARCHAR(80),
                    mapx VARCHAR(80),
                    mapy VARCHAR(80),
                    name VARCHAR(300) NOT NULL,
                    approved BOOLEAN,
                    source VARCHAR(80),
                    region VARCHAR(120),
                    tags VARCHAR(1000),
                    pet_info VARCHAR(255),
                    barrier_free_info VARCHAR(255),
                    walking_info VARCHAR(255),
                    camping_info VARCHAR(255)
                )
                """);
        execute("CREATE TABLE IF NOT EXISTS favorites (id BIGINT AUTO_INCREMENT PRIMARY KEY, created_at TIMESTAMP, user_id VARCHAR(255), place_id BIGINT NOT NULL)");
        execute("CREATE TABLE IF NOT EXISTS follows (id BIGINT AUTO_INCREMENT PRIMARY KEY, follower_id VARCHAR(255), following_id VARCHAR(255), created_at TIMESTAMP)");
        execute("CREATE TABLE IF NOT EXISTS recent_views (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(255), viewed_at TIMESTAMP, place_id BIGINT NOT NULL)");
        execute("CREATE TABLE IF NOT EXISTS search_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, keyword VARCHAR(255), searched_at TIMESTAMP, user_id VARCHAR(255))");
        execute("""
                CREATE TABLE IF NOT EXISTS reviews (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    created_at TIMESTAMP,
                    rating INT NOT NULL,
                    text VARCHAR(2000),
                    user_id VARCHAR(255),
                    place_id BIGINT NOT NULL,
                    like_count INT NOT NULL,
                    liked_by_json VARCHAR(4000)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS notices (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    body VARCHAR(4000),
                    created_at TIMESTAMP,
                    title VARCHAR(300),
                    body_size VARCHAR(40),
                    body_weight VARCHAR(40),
                    image_url VARCHAR(1000),
                    updated_at TIMESTAMP
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS notifications (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    created_at TIMESTAMP,
                    message VARCHAR(1200),
                    read_flag BOOLEAN NOT NULL,
                    reference_id VARCHAR(120),
                    reference_type VARCHAR(120),
                    title VARCHAR(300),
                    type VARCHAR(80),
                    user_id VARCHAR(255)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS inquiries (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    admin_reply VARCHAR(3000),
                    body VARCHAR(3000),
                    created_at TIMESTAMP,
                    replied_at TIMESTAMP,
                    status VARCHAR(80),
                    title VARCHAR(300),
                    updated_at TIMESTAMP,
                    user_id VARCHAR(255)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS place_submissions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    address VARCHAR(600),
                    category VARCHAR(80),
                    category_label VARCHAR(120),
                    created_at TIMESTAMP,
                    description VARCHAR(2000),
                    name VARCHAR(300),
                    region VARCHAR(120),
                    user_id VARCHAR(255),
                    reviewed_at TIMESTAMP,
                    status VARCHAR(40)
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS diary_entries (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    body VARCHAR(3000),
                    created_at TIMESTAMP,
                    image_url LONGTEXT,
                    place_name VARCHAR(300),
                    title VARCHAR(300),
                    updated_at TIMESTAMP,
                    user_id VARCHAR(255),
                    visited_date DATE,
                    badge_stickers VARCHAR(3000),
                    drawing_image LONGTEXT,
                    font_family VARCHAR(80),
                    page_color VARCHAR(30),
                    weather_icon VARCHAR(30),
                    drawing_layout VARCHAR(20),
                    drawingx INT,
                    drawingy INT
                )
                """);

        ensureColumn("places", "region", "VARCHAR(120)");
        ensureColumn("places", "tags", "VARCHAR(1000)");
        ensureColumn("places", "pet_info", "VARCHAR(255)");
        ensureColumn("places", "barrier_free_info", "VARCHAR(255)");
        ensureColumn("places", "walking_info", "VARCHAR(255)");
        ensureColumn("places", "camping_info", "VARCHAR(255)");
        ensureColumn("user_settings", "notification_enabled", "BOOLEAN");
    }

    private void seedPlaces() {
        if (count("SELECT COUNT(*) FROM places") > 0) {
            return;
        }
        List<Map<String, String>> seeds = List.of(
                seed("순포해변", "강원특별자치도 강릉시 사천면 해안로 722", "강원도", "tour", "관광지", "82", "한적함", "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=1000&q=80", "바다,해변,해수욕장,산책", "반려동물 동반 가능성 높음", "무장애 정보 확인 필요", "해안 산책 추천", ""),
                seed("가새기오름", "제주특별자치도 제주시 오라삼동", "제주도", "walk", "산책길", "76", "보통", "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=1000&q=80", "오름,산책길,전망", "반려동물 확인 필요", "일부 구간 경사 있음", "오름길 코스", ""),
                seed("오대산 소금강계곡", "강원특별자치도 강릉시 연곡면 삼산리", "강원도", "nature", "계곡", "88", "한적함", "https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?auto=format&fit=crop&w=1000&q=80", "계곡,숲길,자연", "반려동물 제한 가능", "무장애 정보 확인 필요", "숲길 산책 추천", ""),
                seed("묵호등대", "강원특별자치도 동해시 해맞이길 289", "강원도", "tour", "관광지", "72", "보통", "https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?auto=format&fit=crop&w=1000&q=80", "전망대,바다,사진", "반려동물 확인 필요", "계단 구간 있음", "해안 산책 가능", ""),
                seed("을왕리해수욕장", "인천광역시 중구 용유서로302번길 16-15", "인천", "tour", "관광지", "61", "붐빔", "https://images.unsplash.com/photo-1471922694854-ff1b63b20054?auto=format&fit=crop&w=1000&q=80", "바다,해수욕장,노을", "반려동물 확인 필요", "무장애 접근 일부 가능", "해변 산책 가능", ""),
                seed("북한산 둘레길", "서울특별시 강북구 우이동", "서울", "walk", "산책길", "84", "보통", "https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=1000&q=80", "둘레길,산책길,숲길", "반려동물 동반 가능성 높음", "일부 구간 경사 있음", "두루누비 산책길 후보", ""),
                seed("한탄강 캠핑장", "경기도 포천시 영북면", "경기도", "camping", "캠핑", "79", "보통", "https://images.unsplash.com/photo-1504280390367-361c6d9f38f4?auto=format&fit=crop&w=1000&q=80", "캠핑,계곡,차박", "반려동물 확인 필요", "무장애 정보 확인 필요", "", "고캠핑 후보"),
                seed("감천문화마을 골목", "부산광역시 사하구 감내2로 203", "부산", "culture", "전시", "67", "보통", "https://images.unsplash.com/photo-1538485399081-7191377e8241?auto=format&fit=crop&w=1000&q=80", "사진,골목,전시", "반려동물 확인 필요", "계단 구간 많음", "마을 산책 가능", ""),
                seed("전주 한옥마을 뒷골목", "전북특별자치도 전주시 완산구 기린대로 99", "전북", "history", "역사", "74", "붐빔", "https://images.unsplash.com/photo-1544989164-31dc3c645987?auto=format&fit=crop&w=1000&q=80", "역사,한옥,맛집", "반려동물 확인 필요", "무장애 접근 일부 가능", "도보 여행 가능", ""),
                seed("대전 장태산 숲길", "대전광역시 서구 장안로 461", "대전", "walk", "숲길", "86", "한적함", "https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=1000&q=80", "숲길,산책,전망", "반려동물 확인 필요", "무장애 정보 확인 필요", "메타세쿼이아 산책길", "")
        );
        for (Map<String, String> seed : seeds) {
            jdbc.update("""
                    INSERT INTO places(name,address,region,category,category_label,hidden_score,level,concentration,image,tags,pet_info,barrier_free_info,walking_info,camping_info,approved,source,mapx,mapy)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, seed.get("name"), seed.get("address"), seed.get("region"), seed.get("category"), seed.get("label"),
                    seed.get("score"), seed.get("level"), seed.get("level"), seed.get("image"), seed.get("tags"),
                    seed.get("pet"), seed.get("barrier"), seed.get("walk"), seed.get("camping"), true, "rebuild-seed", "", "");
        }
    }

    private void seedDefaultUsers() {
        if (count("SELECT COUNT(*) FROM user_accounts WHERE username=?", "test01") == 0) {
            jdbc.update("""
                    INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, "test01@soomgil.local", true, "테스트사용자", LocalDateTime.now(), hashPassword("Test01!23"), "01000000001", true, true, "test01", "local", "test01");
            upsertUserSetting("test01", "테스트사용자", "test01@soomgil.local", "01000000001");
        }
        if (count("SELECT COUNT(*) FROM user_accounts WHERE username=?", "demo") == 0) {
            jdbc.update("""
                    INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, "demo@soomgil.local", true, "데모사용자", LocalDateTime.now(), hashPassword("Demo!1234"), "01000000000", true, true, "demo", "local", "demo");
            upsertUserSetting("demo", "데모사용자", "demo@soomgil.local", "01000000000");
        }
    }

    private void seedNotice() {
        if (count("SELECT COUNT(*) FROM notices") == 0) {
            jdbc.update("INSERT INTO notices(title,body,body_size,body_weight,image_url,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    "숨길 재구축 안내", "복구 실패에 대비해 기본 서비스를 다시 구성했습니다.", "large", "bold", "", LocalDateTime.now(), LocalDateTime.now());
        }
    }

    private Map<String, String> seed(String name, String address, String region, String category, String label,
                                     String score, String level, String image, String tags, String pet,
                                     String barrier, String walk, String camping) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("address", address);
        map.put("region", region);
        map.put("category", category);
        map.put("label", label);
        map.put("score", score);
        map.put("level", level);
        map.put("image", image);
        map.put("tags", tags);
        map.put("pet", pet);
        map.put("barrier", barrier);
        map.put("walk", walk);
        map.put("camping", camping);
        return map;
    }

    private Map<String, Object> placeMap(Map<String, Object> row) {
        double hiddenScore = parseDouble(value(row, "hidden_score"));
        double hiddenRating = Math.max(0, Math.min(5, hiddenScore / 20.0));
        String level = textOr(value(row, "level"), crowdLabel(parseDouble(value(row, "concentration"))));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", number(value(row, "id")));
        map.put("name", text(value(row, "name")));
        map.put("address", text(value(row, "address")));
        map.put("region", text(value(row, "region")).isBlank() ? extractRegion(text(value(row, "address"))) : text(value(row, "region")));
        map.put("category", text(value(row, "category")));
        map.put("categoryLabel", text(value(row, "category_label")));
        map.put("image", textOr(value(row, "image"), "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=1000&q=80"));
        map.put("hiddenScore", Math.round(hiddenScore * 10.0) / 10.0);
        map.put("hiddenRating", Math.round(hiddenRating * 10.0) / 10.0);
        map.put("crowd", level);
        map.put("level", level);
        map.put("tags", text(value(row, "tags")));
        map.put("petInfo", textOr(value(row, "pet_info"), "반려동물 정보 확인 필요"));
        map.put("barrierFreeInfo", textOr(value(row, "barrier_free_info"), "무장애 정보 확인 필요"));
        map.put("walkingInfo", text(value(row, "walking_info")));
        map.put("campingInfo", text(value(row, "camping_info")));
        map.put("mapx", text(value(row, "mapx")));
        map.put("mapy", text(value(row, "mapy")));
        return map;
    }

    private Map<String, Object> reviewMap(Map<String, Object> row) {
        return Map.of(
                "id", number(value(row, "id")),
                "placeId", number(value(row, "place_id")),
                "text", text(value(row, "text")),
                "userId", "익명",
                "ownerId", text(value(row, "user_id")),
                "createdAt", text(value(row, "created_at")),
                "likeCount", number(value(row, "like_count"))
        );
    }

    private Map<String, Object> noticeMap(Map<String, Object> row) {
        return Map.of(
                "id", number(value(row, "id")),
                "title", text(value(row, "title")),
                "body", text(value(row, "body")),
                "bodySize", textOr(value(row, "body_size"), "normal"),
                "bodyWeight", textOr(value(row, "body_weight"), "normal"),
                "imageUrl", text(value(row, "image_url")),
                "createdAt", text(value(row, "created_at")),
                "updatedAt", text(value(row, "updated_at"))
        );
    }

    private Map<String, Object> notificationMap(Map<String, Object> row) {
        return Map.of(
                "id", number(value(row, "id")),
                "type", text(value(row, "type")),
                "title", textOr(value(row, "title"), "새로운 알림이 도착했어요!"),
                "message", text(value(row, "message")),
                "read", bool(value(row, "read_flag")),
                "referenceType", text(value(row, "reference_type")),
                "referenceId", text(value(row, "reference_id")),
                "createdAt", text(value(row, "created_at"))
        );
    }

    private Map<String, Object> inquiryMap(Map<String, Object> row) {
        return Map.of(
                "id", number(value(row, "id")),
                "userId", text(value(row, "user_id")),
                "title", text(value(row, "title")),
                "body", text(value(row, "body")),
                "status", textOr(value(row, "status"), "대기"),
                "adminReply", text(value(row, "admin_reply")),
                "createdAt", text(value(row, "created_at")),
                "updatedAt", text(value(row, "updated_at"))
        );
    }

    private Map<String, Object> submissionMap(Map<String, Object> row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", number(value(row, "id")));
        map.put("userId", text(value(row, "user_id")));
        map.put("name", text(value(row, "name")));
        map.put("address", text(value(row, "address")));
        map.put("region", text(value(row, "region")));
        map.put("category", text(value(row, "category")));
        map.put("categoryLabel", text(value(row, "category_label")));
        map.put("description", text(value(row, "description")));
        map.put("status", textOr(value(row, "status"), "대기"));
        map.put("createdAt", text(value(row, "created_at")));
        return map;
    }

    private Map<String, Object> diaryMap(Map<String, Object> row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", number(value(row, "id")));
        map.put("title", text(value(row, "title")));
        map.put("body", text(value(row, "body")));
        map.put("placeName", text(value(row, "place_name")));
        map.put("visitedDate", text(value(row, "visited_date")));
        map.put("weatherIcon", textOr(value(row, "weather_icon"), "sun"));
        map.put("pageColor", textOr(value(row, "page_color"), "#ffffff"));
        map.put("fontFamily", textOr(value(row, "font_family"), "Pretendard"));
        map.put("imageUrl", text(value(row, "image_url")));
        map.put("drawingImage", text(value(row, "drawing_image")));
        map.put("drawingLayout", textOr(value(row, "drawing_layout"), "inline"));
        map.put("badgeStickers", textOr(value(row, "badge_stickers"), "[]"));
        map.put("createdAt", text(value(row, "created_at")));
        return map;
    }

    private Map<String, Object> userResponse(String userId) {
        ensureUserSetting(userId);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_settings WHERE user_id=?", userId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", userId);
        map.put("username", textOr(value(row, "username"), userId));
        map.put("fullName", text(value(row, "full_name")));
        map.put("nickname", textOr(value(row, "nickname"), userId));
        map.put("email", text(value(row, "email")));
        map.put("phone", text(value(row, "phone")));
        map.put("profileImage", textOr(value(row, "profile_image"), "👤"));
        map.put("profileBorder", textOr(value(row, "profile_border"), "#6d5dfc"));
        map.put("displayBadgeIds", readStringList(text(value(row, "display_badge_ids_json"))));
        map.put("interests", readStringList(text(value(row, "interests_json"))));
        map.put("darkMode", bool(value(row, "dark_mode")));
        map.put("notificationEnabled", !Objects.equals(value(row, "notification_enabled"), Boolean.FALSE));
        map.put("address", Map.of(
                "sido", text(value(row, "address_sido")),
                "sigungu", text(value(row, "address_sigungu")),
                "eupmyeondong", text(value(row, "address_eupmyeondong"))
        ));
        map.put("stats", userStats(userId));
        return map;
    }

    private List<Map<String, Object>> people(String sql, String userId) {
        return jdbc.queryForList(sql, userId).stream().map(row -> {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("userId", text(value(row, "user_id")));
            person.put("username", textOr(value(row, "username"), text(value(row, "user_id"))));
            person.put("nickname", textOr(value(row, "nickname"), text(value(row, "user_id"))));
            person.put("profileImage", textOr(value(row, "profile_image"), "👤"));
            person.put("profileBorder", textOr(value(row, "profile_border"), "#6d5dfc"));
            return person;
        }).toList();
    }

    private List<Map<String, Object>> nearbyHiddenPlaces(long currentId) {
        Map<String, Object> current = placeMap(findPlaceRow(currentId).orElse(Map.of()));
        String region = text(current.get("region"));
        return places("", region, "", "hidden").stream()
                .filter(place -> number(place.get("id")) != currentId)
                .limit(2)
                .toList();
    }

    private List<Map<String, Object>> halfDayCourse(Map<String, Object> place) {
        String region = text(place.get("region"));
        List<Map<String, Object>> course = new ArrayList<>();
        course.add(Map.of("step", "오전", "title", text(place.get("name")), "description", region + "의 대표 포인트를 먼저 둘러봅니다."));
        course.add(Map.of("step", "점심", "title", "근처 식사/카페", "description", "혼잡도가 낮은 주변 식당이나 카페를 짧게 연결합니다."));
        course.add(Map.of("step", "오후", "title", "숨은 산책 코스", "description", "방문객이 몰리는 시간을 피해 가까운 산책길로 마무리합니다."));
        return course;
    }

    private String recommendationReason(Map<String, Object> place) {
        String region = text(place.get("region"));
        String crowd = text(place.get("crowd"));
        String category = textOr(place.get("categoryLabel"), "장소");
        String tags = text(place.get("tags")).replace(",", ", ");
        if ("한적함".equals(crowd)) {
            return region + "에서 비교적 한적하게 즐길 수 있는 " + category + "입니다. " + tags + " 흐름이 있어 짧은 여행 코스로 묶기 좋습니다.";
        }
        if ("붐빔".equals(crowd)) {
            return region + "의 인기 " + category + "라 방문 시간대를 조절하는 편이 좋습니다. 대신 주변 편의시설과 연계성이 좋습니다.";
        }
        return region + "에서 접근성과 분위기의 균형이 좋은 " + category + "입니다. 혼잡도와 장소 특성을 함께 보면 반나절 코스에 적합합니다.";
    }

    private Comparator<Map<String, Object>> placeComparator(String sort) {
        return switch (sort) {
            case "hidden" -> Comparator.comparingDouble((Map<String, Object> p) -> parseDouble(p.get("hiddenScore"))).reversed();
            case "name" -> Comparator.comparing(p -> text(p.get("name")));
            case "crowd" -> Comparator.comparing(p -> text(p.get("crowd")));
            default -> Comparator.comparingDouble((Map<String, Object> p) -> parseDouble(p.get("hiddenScore"))).reversed();
        };
    }

    private boolean matchesQuery(Map<String, Object> place, String q) {
        if (q.isBlank()) {
            return true;
        }
        String merged = normalize(String.join(" ",
                text(place.get("name")),
                text(place.get("address")),
                text(place.get("region")),
                text(place.get("categoryLabel")),
                text(place.get("tags")),
                text(place.get("petInfo")),
                text(place.get("barrierFreeInfo"))
        ));
        return merged.contains(q) || matchesAlias(merged, q);
    }

    private boolean matchesAlias(String text, String q) {
        Map<String, List<String>> aliases = Map.of(
                "바다", List.of("해변", "해수욕장", "해안", "노을"),
                "해변", List.of("바다", "해수욕장", "해안"),
                "산책", List.of("둘레길", "숲길", "산책길", "트레킹"),
                "캠핑", List.of("야영장", "글램핑", "차박", "고캠핑"),
                "계곡", List.of("폭포", "물놀이", "숲"),
                "무장애", List.of("장애인", "휠체어", "배리어프리"),
                "반려동물", List.of("강아지", "애견", "펫", "동반")
        );
        return aliases.getOrDefault(q, List.of()).stream().anyMatch(alias -> normalize(text).contains(normalize(alias)));
    }

    private void createPlaceFromSubmission(Map<String, Object> sub) {
        String name = text(sub.get("name"));
        String address = text(sub.get("address"));
        if (count("SELECT COUNT(*) FROM places WHERE name=? AND address=?", name, address) > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO places(name,address,region,category,category_label,hidden_score,level,concentration,image,tags,approved,source)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, name, address, text(sub.get("region")), textOr(sub.get("category"), "tour"),
                textOr(sub.get("categoryLabel"), "관광지"), "70", "확인 필요", "확인 필요", "",
                textOr(sub.get("description"), "사용자 제보"), true, "submission");
    }

    private void saveSearchLog(String userId, String keyword) {
        if (!keyword.isBlank()) {
            jdbc.update("INSERT INTO search_logs(keyword,searched_at,user_id) VALUES(?,?,?)", keyword, LocalDateTime.now(), userId);
        }
    }

    private void ensurePlace(long id) {
        if (count("SELECT COUNT(*) FROM places WHERE id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다.");
        }
    }

    private Optional<Map<String, Object>> findPlaceRow(long id) {
        try {
            return Optional.of(jdbc.queryForMap("SELECT * FROM places WHERE id=?", id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> findAccount(String identifier) {
        try {
            return Optional.of(jdbc.queryForMap("SELECT * FROM user_accounts WHERE username=? OR email=?", identifier, identifier));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Map<String, Object> inquiry(long id) {
        return inquiryMap(jdbc.queryForMap("SELECT * FROM inquiries WHERE id=?", id));
    }

    private Map<String, Object> submission(long id) {
        return submissionMap(jdbc.queryForMap("SELECT * FROM place_submissions WHERE id=?", id));
    }

    private Map<String, Object> diary(long id) {
        return diaryMap(jdbc.queryForMap("SELECT * FROM diary_entries WHERE id=?", id));
    }

    private void upsertUserSetting(String userId, String fullName, String email, String phone) {
        ensureUserSetting(userId);
        jdbc.update("""
                UPDATE user_settings SET username=?, full_name=?, nickname=?, email=?, phone=?, email_verified=?, phone_verified=?, privacy_consent=?, logged_in=?, notification_enabled=?
                WHERE user_id=?
                """, userId, fullName, userId, email, phone, true, !phone.isBlank(), true, true, true, userId);
    }

    private void ensureUserSetting(String userId) {
        if (count("SELECT COUNT(*) FROM user_settings WHERE user_id=?", userId) == 0) {
            jdbc.update("""
                    INSERT INTO user_settings(user_id,interests_json,dark_mode,share_count,nickname,profile_border,profile_image,joined_at,logged_in,username,display_badge_ids_json,notification_enabled)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, userId, "[]", false, 0, userId, "#6d5dfc", "👤", LocalDateTime.now(), false, userId, "[]", true);
        }
    }

    private void createNotification(String userId, String type, String title, String message, String referenceType, String referenceId) {
        jdbc.update("""
                INSERT INTO notifications(user_id,type,title,message,reference_type,reference_id,read_flag,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, userId, type, title, message, referenceType, referenceId, false, LocalDateTime.now());
    }

    private void requireAdmin(String code) {
        if (!Objects.equals(adminCode, code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 코드가 올바르지 않습니다.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void rejectBannedText(String text) {
        String normalized = normalize(text);
        for (String banned : BANNED_WORDS) {
            if (normalized.contains(normalize(banned))) {
                throw badRequest("사용할 수 없는 표현이 포함되어 있습니다.");
            }
        }
    }

    private String requireText(Object value, String message) {
        String text = text(value).trim();
        if (text.isBlank()) {
            throw badRequest(message);
        }
        return text;
    }

    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt, 120000);
            return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Password hashing failed", exception);
        }
    }

    private boolean verifyPassword(String password, String stored) {
        try {
            if (stored.startsWith("oauth$")) {
                return false;
            }
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private long insertAndGetId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        return count("SELECT MAX(id) FROM notices");
    }

    private long count(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.longValue();
    }

    private void execute(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ignored) {
            // Existing restored MariaDB schemas may already have stricter column definitions.
        }
    }

    private void ensureColumn(String table, String column, String type) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Exception ignored) {
            // Column already exists or the database refused a duplicate add.
        }
    }

    private Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String textOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private long number(Object value) {
        try {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.parseLong(text(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double parseDouble(Object value) {
        try {
            return Double.parseDouble(text(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String normalize(String value) {
        return text(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "user" + Math.abs(normalized.hashCode());
        }
        if (normalized.length() < 4) {
            normalized = normalized + "0000";
        }
        return normalized.substring(0, Math.min(20, normalized.length()));
    }

    private String defaultUser(Object userId) {
        String text = text(userId);
        return text.isBlank() ? "guest" : text;
    }

    private String toCamel(String snake) {
        StringBuilder builder = new StringBuilder();
        boolean upper = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                builder.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String extractRegion(String address) {
        if (address.isBlank()) {
            return "";
        }
        String first = address.split("\\s+")[0];
        return first.replace("특별자치도", "도").replace("광역시", "").replace("특별시", "");
    }

    private String crowdLabel(double concentration) {
        if (concentration >= 75) {
            return "붐빔";
        }
        if (concentration >= 35) {
            return "보통";
        }
        return "한적함";
    }
}
