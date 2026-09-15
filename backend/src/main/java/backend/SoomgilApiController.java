package backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;

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

@RestController
@RequestMapping("/api")
public class SoomgilApiController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{3,19}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,72}$");
    private static final List<String> BANNED_WORDS = List.of(
            "씨발", "시발", "ㅅㅂ", "병신", "ㅂㅅ", "섹스", "야동", "좌빨", "수꼴"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SessionAuth auth;
    private final ProgressService progress;
    private final TourCatalog tourCatalog;
    private final SearchCatalog searchCatalog;
    private final TravelPreferences preferences;
    private final NaverIdentity naverIdentity;
    private final TourDetails tourDetails;
    private final ArchivedCrowd archivedCrowd;
    private final CrowdForecast crowdForecast;
    private final PlaceQueries placeQueries;
    private final TourSyncService tourSync;
    private final EmailVerification emailVerification;
    private final AuthRateLimit authLimits;
    private final DiscoveryRecommendations recommendations;
    private final DiaryProtection diaryProtection;
    private final HttpServletRequest request;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String dummyPasswordHash = passwordEncoder.encode("unused-login-timing-placeholder");
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${soomgil.demo-users:false}")
    private boolean demoUsers;

    @Value("${soomgil.google.client-id:}")
    private String googleClientId;

    public SoomgilApiController(JdbcTemplate jdbc, ObjectMapper mapper, SessionAuth auth, ProgressService progress, TourCatalog tourCatalog, SearchCatalog searchCatalog, TravelPreferences preferences, NaverIdentity naverIdentity, TourDetails tourDetails, ArchivedCrowd archivedCrowd, CrowdForecast crowdForecast, PlaceQueries placeQueries, TourSyncService tourSync, EmailVerification emailVerification, AuthRateLimit authLimits, HttpServletRequest request, DiscoveryRecommendations recommendations, DiaryProtection diaryProtection) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auth = auth;
        this.progress = progress;
        this.tourCatalog = tourCatalog;
        this.searchCatalog = searchCatalog;
        this.preferences = preferences;
        this.naverIdentity = naverIdentity;
        this.tourDetails = tourDetails;
        this.archivedCrowd = archivedCrowd;
        this.crowdForecast = crowdForecast;
        this.placeQueries = placeQueries; this.tourSync = tourSync;
        this.emailVerification = emailVerification; this.authLimits = authLimits; this.request = request;
        this.recommendations = recommendations;
        this.diaryProtection = diaryProtection;
    }

    @PostConstruct
    public void initialize() {
        createSchema();
        diaryProtection.initialize();
        placeQueries.initialize();
        seedPlaces();
        restoreCatalog();
        placeQueries.backfill();
        if (demoUsers) seedDefaultUsers();
        seedNotice();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("ok", true, "service", "soomgil", "time", LocalDateTime.now().toString());
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @GetMapping("/auth/config")
    public Map<String, Object> authConfig() {
        return Map.of("googleClientId", googleClientId, "emailVerificationAvailable", emailVerification.available(), "naverAvailable", naverIdentity.available());
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        return Map.of("user", userResponse(auth.requireUser()));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout() {
        auth.logout();
        return Map.of("ok", true);
    }

    @GetMapping("/places")
    public List<Map<String, Object>> places(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String region,
            @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "recommended") String sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "60") int size) {
        validateSearch(query, region, category, page, size);
        return placeQueries.search(query, region, category, sort, page, size, recommendations.profile(auth.currentUser())).rows().stream().map(this::placeMap).toList();
    }

    @GetMapping("/places/search")
    public Map<String, Object> searchPlaces(@RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String region,
            @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "recommended") String sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "6") int size,
            @RequestParam(defaultValue = "") String kind) {
        validateSearch(query, region, category, page, size);
        if (!List.of("", "place", "course").contains(kind)) throw badRequest("콘텐츠 종류를 확인해주세요.");
        var profile = recommendations.profile(auth.currentUser());
        var result = placeQueries.search(query, region, category, sort, page, size, profile, kind);
        if (page == 1 && !query.isBlank() && result.total() > 0 && !auth.currentUser().isBlank()) {
            saveSearchLog(auth.currentUser(), query); progress.record(auth.currentUser(), "search", SearchCatalog.fold(query));
        }
        return pageResponse(result, profile);
    }
    private void validateSearch(String query, String region, String category, int page, int size) {
        if (query.length() > 100 || region.length() > 30 || category.length() > 30 || page < 1 || page > 100000 || size < 1 || size > 60)
            throw badRequest("검색 조건을 확인해주세요.");
    }
    private Map<String, Object> pageResponse(PlaceQueries.Page page) {
        return pageResponse(page, DiscoveryRecommendations.Profile.guest());
    }
    private Map<String, Object> pageResponse(PlaceQueries.Page page, DiscoveryRecommendations.Profile profile) {
        var items = page.rows().stream().map(row -> {
            var item = placeMap(row);
            if (row.get("distance_meters") instanceof Number distance) item.put("distanceMeters", distance.doubleValue());
            recommendations.explain(item, profile, recommendations.today());
            return item;
        }).toList();
        return Map.of("items", items, "total", page.total(), "page", page.page(), "pageSize", page.size(), "hasMore", page.hasMore(), "source", "database");
    }
    @GetMapping("/places/nearby")
    public Map<String, Object> nearbyPlaces(@RequestParam double mapX, @RequestParam double mapY,
            @RequestParam(defaultValue = "3000") int radius, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "slow") String sort, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "6") int size) {
        validateSearch("", "", category, page, size);
        if (!(mapX > 124 && mapX < 132 && mapY > 32 && mapY < 39) || radius < 500 || radius > 20000) throw badRequest("탐색 범위를 확인해주세요.");
        return pageResponse(placeQueries.nearby(mapX, mapY, radius, category, sort, page, size));
    }
    @GetMapping("/places/home")
    public Object homePlaces() {
        return homeRecommendations().get("items");
    }
    @GetMapping("/places/recommendations")
    public Map<String,Object> homeRecommendations() {
        return recommendations.home(recommendations.profile(auth.currentUser()),this::placeMap);
    }
    @GetMapping("/places/status")
    public Map<String, Object> catalogStatus() {
        var result = new LinkedHashMap<>(tourCatalog.status()); result.put("sync", tourSync.status()); return result;
    }
    @GetMapping("/admin/place-sync")
    public Map<String, Object> syncStatus() { requireAdmin(""); return tourSync.status(); }
    @PostMapping("/admin/place-sync")
    public Map<String, Object> controlSync(@RequestBody Map<String, String> body) {
        requireAdmin("");
        if (!List.of("pause", "resume", "run").contains(body.getOrDefault("action", ""))) throw badRequest("갱신 명령을 확인해주세요.");
        tourSync.control(body.get("action")); return tourSync.status();
    }

    @GetMapping("/places/discover")
    public Map<String, Object> discoverPlaces(@RequestParam(defaultValue = "") String region, @RequestParam(defaultValue = "") String category,
                                              @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Double mapX,
                                              @RequestParam(required = false) Double mapY, @RequestParam(defaultValue = "10000") int radius) {
        if (page < 1 || page > 10000 || region.length() > 30 || category.length() > 30
                || (!region.isBlank() && TourCatalog.legalAreaCode(region).isBlank())
                || (mapX == null) != (mapY == null) || radius < 500 || radius > 20000
                || (mapX != null && !(mapX > 124 && mapX < 132 && mapY > 32 && mapY < 39))) throw badRequest("탐색 범위를 확인해주세요.");
        var feed = tourCatalog.discover(region, category, page, mapX, mapY, radius);
        var items = feed.ids().stream().distinct().map(this::findPlaceRow).flatMap(Optional::stream)
                .filter(this::published).map(this::placeMap).toList();
        return Map.of("items", items, "state", feed.state(), "total", feed.total(), "page", feed.page(), "pageSize", feed.pageSize(),
                "hasMore", feed.hasMore(), "storedTotal", jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE approved=TRUE AND tour_visible=TRUE", Long.class));
    }

    @GetMapping("/places/{id}")
    public Map<String, Object> place(@PathVariable long id) {
        Map<String, Object> row = findPlaceRow(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."));
        Map<String, Object> place = placeMap(row);
        if (!published(row)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다.");
        place = refreshCrowd(place);
        if (!auth.currentUser().isBlank()) {
            String userId = auth.currentUser();
            jdbc.update("DELETE FROM recent_views WHERE user_id=? AND place_id=?", userId, id);
            jdbc.update("INSERT INTO recent_views(user_id,place_id,viewed_at) VALUES(?,?,?)", userId, id, LocalDateTime.now());
            progress.record(userId, "view", String.valueOf(id));
        }
        var profile = recommendations.profile(auth.currentUser());
        recommendations.explain(place, profile, recommendations.today());
        var nearby = recommendations.nearby(place, profile, this::placeMap);
        place.put("halfDayCourse", halfDayCourse(place, nearby));
        place.put("nearbyHiddenPlaces", nearby);
        return place;
    }

    @GetMapping("/places/{id}/crowd-info")
    public Map<String, Object> crowdInfo(@PathVariable long id) {
        var row = findPlaceRow(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."));
        if (!published(row)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다.");
        return refreshCrowd(placeMap(row));
    }

    private Map<String, Object> refreshCrowd(Map<String, Object> place) {
        if ("course".equals(place.get("contentKind"))) { place.put("crowdStatus","unsupported"); return place; }
        String source = text(place.get("source"));
        if (!source.startsWith("tour-api:") && !source.equals("recovered-tour-api")) {
            place.put("crowdStatus", "unsupported");
            return place;
        }
        // Catalog metadata is refreshed by the DB sync, not by each visible card.
        crowdForecast.refresh(place);
        crowdForecast.apply(place);
        return place;
    }

    @GetMapping("/places/{id}/visit-info")
    public Map<String, Object> visitInfo(@PathVariable long id) {
        var row = findPlaceRow(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."));
        if (!published(row)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다.");
        return tourDetails.get(placeMap(row));
    }

    @GetMapping("/places/{id}/reviews")
    public List<Map<String, Object>> reviews(
            @PathVariable long id,
            @RequestParam(defaultValue = "latest") String sort
    ) {
        ensurePlace(id);
        String order = "likes".equals(sort) ? "like_count DESC, created_at DESC" : "created_at DESC";
        return jdbc.queryForList("SELECT * FROM reviews WHERE place_id=? ORDER BY " + order, id).stream()
                .map(this::reviewMap)
                .toList();
    }

    @PostMapping("/places/{id}/reviews")
    @Transactional
    public Map<String, Object> createReview(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String userId = defaultUser(body.get("userId"));
        String text = requireText(body.get("text"), "후기 내용을 입력해주세요.");
        rejectBannedText(text);
        if (text.length() > 2000) throw badRequest("후기는 2,000자까지 입력할 수 있습니다.");
        ensurePlace(id);
        jdbc.update(
                "INSERT INTO reviews(created_at,rating,text,user_id,place_id,like_count,liked_by_json) VALUES(?,?,?,?,?,?,?)",
                LocalDateTime.now(), 0, text, userId, id, 0, "[]"
        );
        progress.record(userId, "review", String.valueOf(id));
        return Map.of("ok", true, "reviews", reviews(id, "latest"));
    }

    @DeleteMapping("/reviews/{id}")
    public Map<String, Object> deleteReview(@PathVariable long id) {
        String userId = auth.requireUser();
        requireOwned("reviews", id, userId);
        jdbc.update("DELETE FROM reviews WHERE id=? AND user_id=?", id, userId);
        return Map.of("ok", true);
    }

    @PostMapping("/reviews/{id}/like")
    @Transactional
    public Map<String, Object> likeReview(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String userId = defaultUser(body.get("userId"));
        Map<String, Object> review = jdbc.queryForMap("SELECT * FROM reviews WHERE id=? FOR UPDATE", id);
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
    public Map<String, Object> usernameExists(@RequestParam(defaultValue = "") String username) {
        authLimits.take("username-check", AuthRateLimit.client(request), 30, java.time.Duration.ofMinutes(15));
        if (!USERNAME_PATTERN.matcher(username).matches()) throw badRequest("아이디 형식을 확인해주세요.");
        return Map.of("usernameExists", count("SELECT COUNT(*) FROM user_accounts WHERE username=?", username) > 0 || auth.isReservedUsername(username));
    }

    private Map<String, Object> userExists(String username, String email, String phone) {
        return Map.of(
                "usernameExists", !username.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE username=?", username) > 0,
                "emailExists", !email.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE email=?", email) > 0,
                "phoneExists", !phone.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE phone=?", phone) > 0
        );
    }

    @PostMapping("/auth/signup")
    @Transactional
    public Map<String, Object> signup(@RequestBody Map<String, Object> body) {
        String fullName = text(body.get("fullName")).trim();
        boolean emailOnly = text(body.get("username")).isBlank();
        String username = (emailOnly ? "u" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16) : text(body.get("username"))).trim().toLowerCase(Locale.ROOT);
        String nickname = requireText(emailOnly ? body.get("nickname") : body.getOrDefault("nickname", username.substring(0, Math.min(16, username.length()))), "닉네임을 입력해주세요.");
        String password = requireText(body.get("password"), "비밀번호를 입력해주세요.");
        String email = requireText(body.get("email"), "이메일을 입력해주세요.").toLowerCase(Locale.ROOT);
        String phone = text(body.get("phone")).replaceAll("[\\s-]", "");
        boolean consent = bool(body.get("privacyConsent"));

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw badRequest("아이디는 영문 소문자로 시작하고 영문/숫자/_ 포함 4~20자여야 합니다.");
        }
        if (auth.isReservedUsername(username)) throw badRequest("사용할 수 없는 아이디입니다.");
        rejectBannedText(nickname);
        if (!nickname.matches("^[가-힣A-Za-z0-9_]{2,16}$")) throw badRequest("닉네임은 한글, 영문, 숫자, _로 2~16자까지 입력해주세요.");
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw badRequest("이메일 형식을 확인해주세요.");
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw badRequest("비밀번호는 영문 대소문자, 숫자, 특수문자를 포함한 8~72자여야 합니다.");
        }
        if (!password.equals(text(body.get("passwordConfirm")))) throw badRequest("비밀번호 확인이 일치하지 않습니다.");
        if (!phone.isBlank() && !phone.matches("^01[016789]\\d{7,8}$")) throw badRequest("휴대폰 번호를 확인해주세요.");
        if (fullName.length() > 50 || email.length() > 254) throw badRequest("입력한 내용이 너무 깁니다.");
        if (!consent) {
            throw badRequest("개인정보보호 동의가 필요합니다.");
        }
        rejectBannedText(username);

        emailVerification.consume(body.get("emailProof"), email, "signup", "");
        Map<String, Object> exists = userExists(username, email, phone);
        if (bool(exists.get("usernameExists")) || bool(exists.get("emailExists")) || bool(exists.get("phoneExists"))) {
            throw badRequest("이미 가입된 사용자입니다.");
        }

        jdbc.update("""
                INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, email, true, fullName, LocalDateTime.now(), hashPassword(password), phone.isBlank() ? null : phone, false, consent, username, "local", username);
        upsertUserSetting(username, fullName, email, phone);
        jdbc.update("UPDATE user_settings SET nickname=?,email_verified=TRUE WHERE user_id=?", nickname, username);
        preferences.createForNewUser(username);
        auth.login(username, false);
        createNotification(username, "welcome", "새로운 알림이 도착했어요!", "숨길 가입이 완료되었습니다.", "user", username);
        return Map.of("ok", true, "user", userResponse(username));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        authLimits.take("login-ip", AuthRateLimit.client(request), 30, java.time.Duration.ofMinutes(15));
        String identifier = requireText(body.get("identifier"), "아이디 또는 이메일을 입력해주세요.").toLowerCase(Locale.ROOT);
        String password = requireText(body.get("password"), "비밀번호를 입력해주세요.");
        if (identifier.length() > 254 || password.length() > 72) throw badRequest("로그인 입력 길이를 확인해주세요.");
        if (!USERNAME_PATTERN.matcher(identifier).matches() && !EMAIL_PATTERN.matcher(identifier).matches()) {
            throw badRequest("아이디 또는 이메일 형식을 확인해주세요.");
        }

        Map<String, Object> account = findAccount(identifier).orElse(null);
        String bucket = account == null ? identifier : text(value(account, "username"));
        authLimits.take("login-account", bucket, 5, java.time.Duration.ofMinutes(15));
        String storedHash = account == null ? dummyPasswordHash : text(value(account, "password_hash"));
        if (storedHash.startsWith("oauth$")) storedHash = dummyPasswordHash;
        boolean valid = verifyPassword(password, storedHash);
        if (account == null || !"local".equals(text(value(account, "auth_provider"))) || !valid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해주세요.");
        }
        String username = text(value(account, "username"));
        authLimits.clear("login-account", username);
        if (storedHash.startsWith("pbkdf2$")) jdbc.update("UPDATE user_accounts SET password_hash=? WHERE username=?", hashPassword(password), username);
        auth.login(username, bool(body.get("autoLogin")));
        jdbc.update("UPDATE user_settings SET logged_in=? WHERE user_id=?", true, username);
        return Map.of("ok", true, "user", userResponse(username));
    }

    @PostMapping("/auth/google")
    @Transactional
    public Map<String, Object> googleLogin(@RequestBody Map<String, Object> body) {
        if (googleClientId.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google 로그인 연결을 준비하고 있습니다.");
        var claims = GoogleIdentity.verify(requireText(body.get("credential"), "Google 인증이 필요합니다."), googleClientId);
        String email = claims.getClaimAsString("email").toLowerCase(Locale.ROOT);
        String fullName = textOr(claims.getClaimAsString("name"), email.substring(0, email.indexOf("@")));
        String providerId = claims.getSubject();
        String username = normalizeUsername(textOr(body.get("username"), email.substring(0, email.indexOf("@"))));
        int suffix = 1;
        String base = username;
        while (auth.isReservedUsername(username) || count("SELECT COUNT(*) FROM user_accounts WHERE username=? AND email<>?", username, email) > 0) {
            username = base + suffix++;
        }

        Optional<Map<String, Object>> account = findAccount(email);
        if (account.isEmpty()) {
            jdbc.update("""
                    INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, email, true, fullName, LocalDateTime.now(), "oauth$google$" + providerId, "google:" + providerId, false, true, username, "google", providerId);
            upsertUserSetting(username, fullName, email, "");
            preferences.createForNewUser(username);
        } else {
            if (!"google".equals(text(value(account.get(), "auth_provider"))) || !providerId.equals(text(value(account.get(), "provider_id")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이 이메일로 가입한 계정이 있습니다. 기존 아이디와 비밀번호로 로그인해주세요.");
            }
            username = text(value(account.get(), "username"));
        }
        auth.login(username, bool(body.get("autoLogin")));
        return Map.of("ok", true, "user", userResponse(username));
    }

    public Optional<String> findNaverUsername(String providerId) {
        return jdbc.queryForList("SELECT username FROM user_accounts WHERE auth_provider='naver' AND provider_id=?", String.class, providerId).stream().findFirst();
    }

    public boolean naverEmailConflict(String email) {
        return !email.isBlank() && count("SELECT COUNT(*) FROM user_accounts WHERE email=?", email.trim().toLowerCase(Locale.ROOT)) > 0;
    }

    public String naverNickname(String supplied) {
        try {
            rejectBannedText(supplied);
            if (supplied.matches("^[가-힣A-Za-z0-9_]{2,16}$")) return supplied;
        } catch (ResponseStatusException ignored) { }
        return "여행친구";
    }

    @Transactional
    public String registerNaver(NaverIdentity.Profile profile, Map<String, Object> body) {
        if (!Boolean.TRUE.equals(body.get("privacyConsent"))) throw badRequest("개인정보 수집·이용 동의가 필요합니다.");
        String nickname = text(body.get("nickname")).trim();
        rejectBannedText(nickname);
        if (!nickname.matches("^[가-힣A-Za-z0-9_]{2,16}$")) throw badRequest("닉네임은 한글, 영문, 숫자, _로 2~16자까지 입력해주세요.");
        var existing = findNaverUsername(profile.id());
        if (existing.isPresent()) return existing.get();
        String email = (profile.email().isBlank() ? text(body.get("email")) : profile.email()).trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) throw badRequest("연락받을 이메일 주소를 입력해주세요.");
        if (naverEmailConflict(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "이 이메일로 가입한 계정이 있어요. 기존 로그인 방법으로 로그인해주세요.");
        String username;
        do { username = "n" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
        while (auth.isReservedUsername(username) || count("SELECT COUNT(*) FROM user_accounts WHERE username=?", username) > 0);
        jdbc.update("""
                INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                VALUES(?,FALSE,'',?,?,NULL,FALSE,TRUE,?,'naver',?)
                """, email, LocalDateTime.now(), "oauth$naver$" + profile.id(), username, profile.id());
        upsertUserSetting(username, "", email, "");
        jdbc.update("UPDATE user_settings SET nickname=? WHERE user_id=?", nickname, username);
        if (!profile.image().isBlank()) jdbc.update("UPDATE user_settings SET profile_image=? WHERE user_id=?", profile.image(), username);
        preferences.createForNewUser(username);
        createNotification(username, "welcome", "새로운 알림이 도착했어요!", "숨길 가입이 완료되었습니다.", "user", username);
        return username;
    }

    @PostMapping("/auth/find-id")
    @Transactional
    public Map<String, Object> findId(@RequestBody Map<String, Object> body) {
        String email = EmailVerification.email(body.get("email"));
        var proof = emailVerification.consume(body.get("emailProof"), email, "find-id", null);
        var account = emailVerification.verifiedAccount(proof);
        return Map.of("username", value(account, "username"), "email", email, "provider", value(account, "auth_provider"));
    }

    @PostMapping("/auth/reset-password")
    @Transactional
    public Map<String, Object> resetPassword(@RequestBody Map<String, Object> body) {
        String email = EmailVerification.email(body.get("email"));
        String password = text(body.get("newPassword"));
        if (!PASSWORD_PATTERN.matcher(password).matches()) throw badRequest("비밀번호는 영문 대소문자, 숫자, 특수문자를 포함한 8~72자여야 합니다.");
        if (!password.equals(text(body.get("passwordConfirm")))) throw badRequest("비밀번호 확인이 일치하지 않습니다.");
        var proof = emailVerification.consume(body.get("emailProof"), email, "reset-password", null);
        var account = emailVerification.verifiedAccount(proof);
        if (!"local".equals(value(account, "auth_provider"))) throw badRequest("소셜 로그인 계정은 Google 또는 네이버에서 비밀번호를 관리해주세요.");
        String username = value(account, "username").toString();
        jdbc.update("UPDATE user_accounts SET password_hash=? WHERE username=?", hashPassword(password), username);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", username);
        emailVerification.passwordChanged(username, email);
        if (username.equals(auth.currentUser())) auth.logout();
        return Map.of("ok", true);
    }

    @PostMapping("/auth/email/request")
    public Map<String, Object> requestEmail(@RequestBody Map<String, Object> body) {
        String purpose = text(body.get("purpose"));
        String owner = (purpose.equals("change-email") || purpose.equals("verify-email")) ? auth.requireUser() : "";
        return emailVerification.request(EmailVerification.email(body.get("email")), purpose, owner, AuthRateLimit.client(request));
    }

    @PostMapping("/auth/email/confirm")
    public Map<String, Object> confirmEmail(@RequestBody Map<String, Object> body) {
        return emailVerification.confirm(text(body.get("challengeId")), text(body.get("code")), AuthRateLimit.client(request));
    }

    @PostMapping("/auth/verify-email")
    @Transactional
    public Map<String, Object> verifyAccountEmail(@RequestBody Map<String, Object> body) {
        String username = auth.requireUser(), email = EmailVerification.email(body.get("email"));
        var proof = emailVerification.consume(body.get("emailProof"), email, "verify-email", username);
        emailVerification.verifiedAccount(proof);
        jdbc.update("UPDATE user_accounts SET email_verified=TRUE WHERE username=?", username);
        jdbc.update("UPDATE user_settings SET email_verified=TRUE WHERE user_id=?", username);
        return Map.of("ok", true);
    }

    @GetMapping("/users/{userId}/settings")
    public Map<String, Object> settings(@PathVariable String userId) {
        ensureUserSetting(userId);
        return userResponse(userId);
    }

    @PutMapping("/users/{userId}/preferences")
    @Transactional
    public Map<String, Object> updatePreferences(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        auth.requireOwner(userId);
        ensureUserSetting(userId);
        preferences.save(userId, body);
        return Map.of("ok", true, "user", userResponse(userId));
    }

    @PutMapping("/users/{userId}/settings")
    @Transactional
    public Map<String, Object> updateSettings(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        ensureUserSetting(userId);
        String[] columns = {"nickname", "profile_image", "profile_border", "interests_json", "display_badge_ids_json",
                "dark_mode", "address_sido", "address_sigungu", "address_eupmyeondong", "notification_enabled"};
        for (String column : columns) {
            String camel = toCamel(column);
            if (body.containsKey(camel) || body.containsKey(column)) {
                Object value = body.containsKey(camel) ? body.get(camel) : body.get(column);
                if ("nickname".equals(column)) {
                    rejectBannedText(text(value));
                    if (!text(value).matches("^[가-힣A-Za-z0-9_]{2,16}$")) throw badRequest("닉네임은 한글, 영문, 숫자, _로 2~16자까지 입력해주세요.");
                }
                if (column.equals("profile_image")) validateImage(text(value));
                if (column.equals("interests_json")) value = preferences.interestsJson(value);
                if (column.equals("display_badge_ids_json")) {
                    var ids = readStringList(text(value));
                    if (ids.size() > 3 || new LinkedHashSet<>(ids).size() != ids.size() || !progress.earnedIds(userId).containsAll(ids)) throw badRequest("획득한 배지만 최대 3개 전시할 수 있습니다.");
                }
                if (column.startsWith("address_") && !text(value).matches("^[가-힣a-zA-Z\\s]{0,50}$")) throw badRequest("주소에는 시·도, 시·군·구, 읍·면·동 이름만 입력해주세요.");
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
                WHERE f.user_id=? AND p.approved=TRUE AND p.tour_visible=TRUE
                ORDER BY f.created_at DESC
                """, userId).stream().map(this::placeMap).toList();
    }

    @PostMapping("/users/{userId}/favorites")
    @Transactional
    public Map<String, Object> addFavorite(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        long placeId = number(body.get("placeId"));
        ensurePlace(placeId);
        jdbc.queryForList("SELECT username FROM user_accounts WHERE username=? FOR UPDATE", userId);
        if (count("SELECT COUNT(*) FROM favorites WHERE user_id=? AND place_id=?", userId, placeId) == 0) {
            jdbc.update("INSERT INTO favorites(user_id,created_at,place_id) VALUES(?,?,?)", userId, LocalDateTime.now(), placeId);
        }
        progress.record(userId, "favorite", String.valueOf(placeId));
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
    @Transactional
    public Map<String, Object> follow(@PathVariable String userId, @PathVariable String targetId) {
        if (userId.equals(targetId)) {
            throw badRequest("자기 자신은 팔로우할 수 없습니다.");
        }
        ensureUserSetting(userId);
        if (findAccount(targetId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        jdbc.queryForList("SELECT username FROM user_accounts WHERE username=? FOR UPDATE", userId);
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
                WHERE fo.follower_id=? AND p.approved=TRUE AND p.tour_visible=TRUE
                ORDER BY p.name
                """, userId).stream().map(this::placeMap).toList();
    }

    @GetMapping("/users/{userId}/notifications")
    public List<Map<String, Object>> notifications(@PathVariable String userId) {
        return jdbc.queryForList("""
                SELECT n.*, CASE WHEN r.notification_id IS NOT NULL THEN TRUE ELSE FALSE END AS user_read
                FROM notifications n LEFT JOIN notification_reads r ON n.id=r.notification_id AND r.user_id=?
                WHERE n.user_id=? OR n.user_id='*'
                ORDER BY n.created_at DESC LIMIT 100
                """, userId, userId).stream().map(row -> {
                    var result = new LinkedHashMap<>(notificationMap(row));
                    result.put("read", bool(value(row, "user_read")) || (!"*".equals(text(value(row, "user_id"))) && bool(value(row, "read_flag"))));
                    return (Map<String, Object>) result;
                }).toList();
    }

    @PutMapping("/users/{userId}/notifications/read")
    public Map<String, Object> readNotifications(@PathVariable String userId) {
        for (Map<String, Object> note : notifications(userId)) markNotification(userId, number(note.get("id")));
        return Map.of("ok", true);
    }

    @PutMapping("/users/{userId}/notifications/{id}/read")
    public Map<String, Object> markNotification(@PathVariable String userId, @PathVariable long id) {
        if (count("SELECT COUNT(*) FROM notifications WHERE id=? AND (user_id=? OR user_id='*')", id, userId) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.");
        if (count("SELECT COUNT(*) FROM notification_reads WHERE user_id=? AND notification_id=?", userId, id) == 0)
            jdbc.update("INSERT INTO notification_reads(user_id,notification_id) VALUES(?,?)", userId, id);
        return Map.of("ok", true);
    }

    @GetMapping("/users/{userId}/recent-views")
    public List<Map<String, Object>> recentViews(@PathVariable String userId) {
        return jdbc.queryForList("SELECT p.* FROM places p JOIN (SELECT place_id, MAX(viewed_at) AS last_view FROM recent_views WHERE user_id=? GROUP BY place_id) r ON r.place_id=p.id WHERE p.approved=TRUE AND p.tour_visible=TRUE ORDER BY r.last_view DESC LIMIT 20", userId).stream().map(this::placeMap).toList();
    }

    @GetMapping("/users/{userId}/reviews")
    public List<Map<String, Object>> myReviews(@PathVariable String userId) {
        return jdbc.queryForList("SELECT r.*, p.name AS place_name FROM reviews r JOIN places p ON p.id=r.place_id WHERE r.user_id=? ORDER BY r.created_at DESC", userId).stream().map(row -> {
            var review = new LinkedHashMap<>(reviewMap(row));
            review.put("placeName", text(value(row, "place_name")));
            return (Map<String, Object>) review;
        }).toList();
    }

    @GetMapping("/users/{userId}/progress")
    public Map<String, Object> userProgress(@PathVariable String userId) { return progress.summary(userId); }

    @GetMapping("/people")
    public List<Map<String, Object>> searchPeople(@RequestParam(defaultValue = "") String query) {
        String q = query.trim();
        if (q.length() < 2 || q.length() > 30) return List.of();
        return people("SELECT s.* FROM user_settings s JOIN user_accounts a ON s.user_id=a.username WHERE LOWER(s.nickname) LIKE LOWER(?) ORDER BY s.nickname LIMIT 20", "%" + q.replace("%", "\\%").replace("_", "\\_") + "%");
    }

    @GetMapping("/users/{userId}/place-submissions")
    public List<Map<String, Object>> userSubmissions(@PathVariable String userId) {
        return jdbc.queryForList("SELECT * FROM place_submissions WHERE user_id=? ORDER BY created_at DESC", userId).stream().map(this::submissionMap).toList();
    }

    @PutMapping("/users/{userId}/account")
    @Transactional
    public Map<String, Object> updateAccount(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        auth.requireOwner(userId);
        authLimits.take("account-password", userId, 5, java.time.Duration.ofMinutes(15));
        var account = findAccount(userId).orElseThrow(() -> badRequest("계정을 찾을 수 없습니다."));
        if (!verifyPassword(text(body.get("currentPassword")), text(value(account, "password_hash")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 비밀번호를 확인해주세요.");
        authLimits.clear("account-password", userId);
        if (body.containsKey("email")) {
            String email = text(body.get("email")).trim().toLowerCase(Locale.ROOT);
            if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 254) throw badRequest("이메일 형식을 확인해주세요.");
            var proof = emailVerification.consume(body.get("emailProof"), email, "change-email", userId);
            emailVerification.verifiedAccount(proof);
            jdbc.update("UPDATE user_accounts SET email=?, email_verified=TRUE WHERE username=?", email, userId);
            jdbc.update("UPDATE user_settings SET email=?, email_verified=TRUE WHERE user_id=?", email, userId);
        }
        if (body.containsKey("phone")) {
            String phone = text(body.get("phone")).replaceAll("[\\s-]", "");
            if (!phone.matches("^01[016789]\\d{7,8}$")) throw badRequest("휴대폰 번호를 확인해주세요.");
            jdbc.update("UPDATE user_accounts SET phone=?, phone_verified=FALSE WHERE username=?", phone, userId);
            jdbc.update("UPDATE user_settings SET phone=?, phone_verified=FALSE WHERE user_id=?", phone, userId);
        }
        if (body.containsKey("newPassword")) {
            String password = text(body.get("newPassword"));
            if (!PASSWORD_PATTERN.matcher(password).matches()) throw badRequest("비밀번호는 영문 대소문자, 숫자, 특수문자를 포함한 8~72자여야 합니다.");
            jdbc.update("UPDATE user_accounts SET password_hash=? WHERE username=?", hashPassword(password), userId);
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", userId);
            emailVerification.passwordChanged(userId, text(value(account, "email")));
            auth.logout();
        }
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
    @Transactional
    public Map<String, Object> createNotice(@RequestParam(defaultValue = "") String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        validateNotice(body);
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
    public Map<String, Object> updateNotice(@PathVariable long id, @RequestParam(defaultValue = "") String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        validateNotice(body);
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
    @Transactional
    public Map<String, Object> deleteNotice(@PathVariable long id, @RequestParam(defaultValue = "") String adminCode) {
        requireAdmin(adminCode);
        jdbc.update("DELETE FROM notification_reads WHERE notification_id IN (SELECT id FROM notifications WHERE reference_type='notice' AND reference_id=?)", String.valueOf(id));
        jdbc.update("DELETE FROM notifications WHERE reference_type='notice' AND reference_id=?", String.valueOf(id));
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
        validateInquiry(body);
        long id = insertAndGetId("""
                INSERT INTO inquiries(user_id,title,body,status,created_at,updated_at) VALUES(?,?,?,?,?,?)
                """, userId, requireText(body.get("title"), "문의 제목을 입력해주세요."),
                requireText(body.get("body"), "문의 내용을 입력해주세요."),
                "대기", LocalDateTime.now(), LocalDateTime.now());
        return Map.of("ok", true, "inquiry", inquiry(id));
    }

    @PutMapping("/users/{userId}/inquiries/{id}")
    public Map<String, Object> updateInquiry(@PathVariable String userId, @PathVariable long id, @RequestBody Map<String, Object> body) {
        requireOwned("inquiries", id, userId);
        validateInquiry(body);
        if ("답변완료".equals(inquiry(id).get("status"))) throw badRequest("답변이 완료된 문의는 수정할 수 없습니다.");
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
public List<Map<String, Object>> adminInquiries(@RequestParam(defaultValue = "") String adminCode) {
        requireAdmin(adminCode);
        return jdbc.queryForList("SELECT * FROM inquiries ORDER BY created_at DESC").stream()
                .map(this::inquiryMap)
                .toList();
    }

    @PutMapping("/admin/inquiries/{id}/reply")
public Map<String, Object> replyInquiry(@PathVariable long id, @RequestParam(defaultValue = "") String adminCode, @RequestBody Map<String, Object> body) {
        requireAdmin(adminCode);
        String reply = requireText(body.get("reply"), "답변 내용을 입력해주세요.");
        Map<String, Object> inquiry = inquiry(id);
        jdbc.update("UPDATE inquiries SET admin_reply=?, replied_at=?, status=?, updated_at=? WHERE id=?",
                reply, LocalDateTime.now(), "답변완료", LocalDateTime.now(), id);
        createNotification(text(inquiry.get("userId")), "inquiry", "새로운 알림이 도착했어요!", "문의 답변이 등록되었습니다.", "inquiry", String.valueOf(id));
        return Map.of("ok", true, "inquiry", inquiry(id));
    }

    @DeleteMapping("/admin/inquiries/{id}")
public Map<String, Object> deleteInquiryAdmin(@PathVariable long id, @RequestParam(defaultValue = "") String adminCode) {
        requireAdmin(adminCode);
        jdbc.update("DELETE FROM inquiries WHERE id=?", id);
        return Map.of("ok", true);
    }

    @PostMapping("/place-submissions")
    @Transactional
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
        progress.record(userId, "submission", String.valueOf(id));
        return Map.of("ok", true, "submission", submission(id));
    }

    @GetMapping("/admin/place-submissions")
public List<Map<String, Object>> placeSubmissions(@RequestParam(defaultValue = "") String adminCode) {
        requireAdmin(adminCode);
        return jdbc.queryForList("SELECT * FROM place_submissions ORDER BY created_at DESC").stream()
                .map(this::submissionMap)
                .toList();
    }

    @PutMapping("/admin/place-submissions/{id}")
    @Transactional
public Map<String, Object> updatePlaceSubmission(@PathVariable long id, @RequestParam(defaultValue = "") String adminCode, @RequestBody Map<String, Object> body) {
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
            if (!List.of("대기", "승인", "반려").contains(status)) throw badRequest("제보 상태를 확인해주세요.");
            jdbc.update("UPDATE place_submissions SET status=?, reviewed_at=? WHERE id=?", status, LocalDateTime.now(), id);
            Map<String, Object> sub = submission(id);
            String userId = text(sub.get("userId"));
            createNotification(userId, "place-submission", "새로운 알림이 도착했어요!", "제보한 장소가 " + status + " 처리되었습니다.", "place-submission", String.valueOf(id));
            if ("승인".equals(status)) {
                createPlaceFromSubmission(sub);
                progress.record(userId, "submission-approved", String.valueOf(id));
            } else {
                jdbc.update("UPDATE places SET approved=FALSE WHERE source=?", "submission:" + id);
            }
        }
        if (!body.containsKey("status") && "승인".equals(submission(id).get("status"))) createPlaceFromSubmission(submission(id));
        return Map.of("ok", true, "submission", submission(id));
    }

    @GetMapping("/users/{userId}/diaries")
    public List<Map<String, Object>> diaries(@PathVariable String userId) {
        return jdbc.queryForList("SELECT * FROM diary_entries WHERE user_id=? ORDER BY visited_date DESC, created_at DESC", userId).stream()
                .map(this::diaryMap)
                .toList();
    }

    @PostMapping("/users/{userId}/diaries")
    @Transactional
    public Map<String, Object> createDiary(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        var draft=diaryProtection.prepare(userId,null,body);
        long id = insertAndGetId("""
                INSERT INTO diary_entries(user_id,title,body,place_name,visited_date,weather_icon,page_color,font_family,image_url,drawing_image,drawing_layout,badge_stickers,storage_bytes,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, userId,draft.title(),draft.body(),draft.placeName(),draft.date(),draft.weather(),draft.color(),draft.font(),draft.image(),draft.drawing(),draft.layout(),draft.stickers(),draft.bytes(),LocalDateTime.now(),LocalDateTime.now());
        progress.record(userId, "diary", String.valueOf(id));
        return Map.of("ok", true, "diary", diary(id));
    }

    @PutMapping("/users/{userId}/diaries/{id}")
    @Transactional
    public Map<String, Object> updateDiary(@PathVariable String userId, @PathVariable long id, @RequestBody Map<String, Object> body) {
        var draft=diaryProtection.prepare(userId,id,body);
        jdbc.update("""
                UPDATE diary_entries SET title=?, body=?, place_name=?, visited_date=?, weather_icon=?, page_color=?, font_family=?, image_url=?, drawing_image=?, drawing_layout=?, badge_stickers=?, storage_bytes=?, updated_at=?
                WHERE id=? AND user_id=?
                """,draft.title(),draft.body(),draft.placeName(),draft.date(),draft.weather(),draft.color(),draft.font(),draft.image(),draft.drawing(),draft.layout(),draft.stickers(),draft.bytes(),LocalDateTime.now(),id,userId);
        return Map.of("ok", true, "diary", diary(id));
    }

    @DeleteMapping("/users/{userId}/diaries/{id}")
    public Map<String, Object> deleteDiary(@PathVariable String userId, @PathVariable long id) {
        jdbc.update("DELETE FROM diary_entries WHERE id=? AND user_id=?", id, userId);
        return Map.of("ok", true);
    }

    @GetMapping("/suggestions")
    public List<String> suggestions(@RequestParam(defaultValue = "") String q) {
        if (q.length() > 100) throw badRequest("검색어는 100자까지 입력할 수 있습니다.");
        if (q.isBlank()) return List.of();
        return searchCatalog.suggestions(q, jdbc.queryForList("SELECT DISTINCT name FROM places WHERE approved=TRUE AND tour_visible=TRUE ORDER BY name", String.class));
    }

    @GetMapping("/suggestions/related")
    public List<String> relatedSuggestions(@RequestParam(defaultValue = "") String q) {
        if (q.length() > 100) throw badRequest("검색어는 100자까지 입력할 수 있습니다.");
        return searchCatalog.related(q);
    }

    private void createSchema() {
        execute("CREATE TABLE IF NOT EXISTS notification_reads (user_id VARCHAR(255) NOT NULL, notification_id BIGINT NOT NULL, PRIMARY KEY(user_id, notification_id))");
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
        ensureColumn("places", "legal_area_code", "VARCHAR(2)");
        ensureColumn("places", "legal_district_code", "VARCHAR(5)");
        ensureColumn("user_settings", "notification_enabled", "BOOLEAN");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_account_provider ON user_accounts(auth_provider,provider_id)");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "user_accounts", "phone")) {
                if (columns.next() && columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls) {
                    try (var statement = connection.createStatement()) {
                        statement.execute(connection.getMetaData().getDatabaseProductName().equals("H2")
                                ? "ALTER TABLE user_accounts ALTER COLUMN phone DROP NOT NULL"
                                : "ALTER TABLE user_accounts MODIFY COLUMN phone VARCHAR(255) NULL");
                    }
                }
            }
            return null;
        });
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "notices", "image_url")) {
                if (columns.next() && columns.getInt("COLUMN_SIZE") < 2_000_000) {
                    boolean h2 = connection.getMetaData().getDatabaseProductName().equals("H2");
                    try (var statement = connection.createStatement()) {
                        statement.execute(h2 ? "ALTER TABLE notices ALTER COLUMN image_url LONGTEXT" : "ALTER TABLE notices MODIFY COLUMN image_url LONGTEXT");
                    }
                }
            }
            return null;
        });
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
        if (count("SELECT COUNT(*) FROM user_accounts WHERE username=?", "test1") == 0) {
            jdbc.update("""
                    INSERT INTO user_accounts(email,email_verified,full_name,joined_at,password_hash,phone,phone_verified,privacy_consent,username,auth_provider,provider_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, "test1@soomgil.local", true, "테스트1", LocalDateTime.now(), hashPassword("Test1!234"), "01000000011", true, true, "test1", "local", "test1");
            upsertUserSetting("test1", "테스트1", "test1@soomgil.local", "01000000011");
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

    private boolean published(Map<String, Object> row) { return bool(value(row, "approved")) && !Boolean.FALSE.equals(value(row, "tour_visible")); }

    private Map<String, Object> placeMap(Map<String, Object> row) {
        var content = PlaceContent.classify(row);
        double hiddenScore = parseDouble(value(row, "hidden_score"));
        double hiddenRating = Math.max(0, Math.min(5, hiddenScore / 20.0));
        var crowd = archivedCrowd.find(text(value(row, "name")), text(value(row, "mapx")), text(value(row, "mapy")), text(value(row, "source")));
        String level = crowd.map(ArchivedCrowd.Observation::label).orElse("확인 필요");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", number(value(row, "id")));
        map.put("name", text(value(row, "name")));
        map.put("address", text(value(row, "address")));
        map.put("region", text(value(row, "region")).isBlank() ? extractRegion(text(value(row, "address"))) : text(value(row, "region")));
        map.put("category", content.category());
        map.put("categoryLabel", content.label());
        map.put("contentTypeId", content.type());
        map.put("contentKind", content.kind());
        map.put("locationAvailable", PlaceContent.coordinates(row));
        map.put("image", PlaceContent.displayImage(row));
        map.put("imageSource", PlaceContent.displayImage(row).isBlank() ? "none" : PlaceContent.officialImage(PlaceContent.displayImage(row)) ? "tourism" : "submission");
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
        map.put("source", text(value(row, "source")));
        map.put("legalAreaCode", text(value(row, "legal_area_code")));
        map.put("legalDistrictCode", text(value(row, "legal_district_code")));
        map.put("imageVerified", PlaceContent.officialImage(PlaceContent.displayImage(row)));
        map.put("ratingVerified", false);
        map.put("crowdVerified", false);
        map.put("crowdAvailable", crowd.isPresent());
        map.put("crowdBasis", crowd.isPresent() ? "archived" : "unknown");
        map.put("crowdSource", crowd.isPresent() ? "legacy-db" : "");
        map.put("crowdValue", crowd.map(ArchivedCrowd.Observation::value).orElse(null));
        map.put("crowdBackupAt", crowd.map(ArchivedCrowd.Observation::backupAt).orElse(null));
        map.put("crowdCollectedAt", null);
        map.put("petInfo", "동반 가능 여부 확인 필요");
        map.put("barrierFreeInfo", "접근성 정보 확인 필요");
        crowdForecast.apply(map);
        if (content.kind().equals("course")) {
            map.put("crowdAvailable",false); map.put("crowdVerified",false); map.put("crowdBasis","unknown"); map.put("crowdValue",null); map.put("crowdStatus","unsupported");
        }
        return map;
    }

    private void restoreCatalog() {
        try (var input = getClass().getResourceAsStream("/data/recovered-places.json")) {
            if (input == null) return;
            List<Map<String, String>> records = mapper.readValue(input, new TypeReference<>() {});
            for (var item : records) {
                String category = item.get("category");
                String label = switch (category) { case "food" -> "음식점"; case "cafe" -> "카페"; case "camping" -> "캠핑"; default -> "관광지"; };
                List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, source FROM places WHERE name=?", item.get("name"));
                if (existing.isEmpty()) {
                    jdbc.update("INSERT INTO places(name,address,region,category,category_label,image,mapx,mapy,approved,source,tags) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            item.get("name"), item.get("address"), extractRegion(item.get("address")), category, label, item.get("image"), item.get("mapx"), item.get("mapy"), true, "recovered-tour-api", item.get("name"));
                } else if ("rebuild-seed".equals(text(value(existing.get(0), "source")))) {
                    jdbc.update("UPDATE places SET address=?,region=?,category=?,category_label=?,image=?,mapx=?,mapy=?,source=? WHERE id=?",
                            item.get("address"), extractRegion(item.get("address")), category, label, item.get("image"), item.get("mapx"), item.get("mapy"), "recovered-tour-api", value(existing.get(0), "id"));
                }
            }
        } catch (java.io.IOException exception) { throw new IllegalStateException("Public place catalog could not be read", exception); }
    }

    private Map<String, Object> reviewMap(Map<String, Object> row) {
        return Map.of(
                "id", number(value(row, "id")),
                "placeId", number(value(row, "place_id")),
                "text", text(value(row, "text")),
                "userId", "익명",
                "mine", auth.currentUser().equals(text(value(row, "user_id"))),
                "liked", readStringList(text(value(row, "liked_by_json"))).contains(auth.currentUser()),
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

    private void validateNotice(Map<String, Object> body) {
        if (text(body.get("title")).length() > 300 || text(body.get("body")).length() > 4000) throw badRequest("공지 제목은 300자, 본문은 4,000자까지 입력할 수 있습니다.");
        validateImage(text(body.get("imageUrl")));
        if (!List.of("normal", "large").contains(textOr(body.get("bodySize"), "normal")) || !List.of("normal", "bold").contains(textOr(body.get("bodyWeight"), "normal"))) throw badRequest("글자 서식을 확인해주세요.");
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
        map.putAll(preferences.read(userId));
        map.put("darkMode", bool(value(row, "dark_mode")));
        map.put("notificationEnabled", !Objects.equals(value(row, "notification_enabled"), Boolean.FALSE));
        map.put("address", Map.of(
                "sido", text(value(row, "address_sido")),
                "sigungu", text(value(row, "address_sigungu")),
                "eupmyeondong", text(value(row, "address_eupmyeondong"))
        ));
        map.put("stats", userStats(userId));
        map.put("role", auth.isAdmin() ? "ADMIN" : "USER");
        map.put("progress", progress.summary(userId));
        findAccount(userId).ifPresent(account -> {
            map.put("emailVerified", bool(value(account, "email_verified")));
            map.put("phoneVerified", bool(value(account, "phone_verified")));
            map.put("authProvider", text(value(account, "auth_provider")));
        });
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
            person.put("following", count("SELECT COUNT(*) FROM follows WHERE follower_id=? AND following_id=?", auth.currentUser(), text(value(row, "user_id"))) > 0);
            return person;
        }).toList();
    }

    private List<Map<String, Object>> halfDayCourse(Map<String, Object> place, List<Map<String,Object>> nearbyPlaces) {
        List<Map<String, Object>> course = new ArrayList<>();
        course.add(Map.of("step", "01", "title", text(place.get("name")), "description", "선택한 장소에서 출발", "placeId", place.get("id")));
        for (var nearby : nearbyPlaces) {
            course.add(Map.of("step", "0" + (course.size() + 1), "title", nearby.get("name"), "description", "출발지에서 직선거리 " + nearby.get("distanceKm") + "km · " + nearby.get("categoryLabel"), "placeId", nearby.get("id")));
        }
        return course;
    }

    private Comparator<Map<String, Object>> placeComparator(String sort) {
        return switch (sort) {
            case "name" -> Comparator.comparing(p -> text(p.get("name")));
            default -> Comparator.<Map<String, Object>>comparingInt(p -> Boolean.TRUE.equals(p.get("imageVerified")) ? 0 : 1).thenComparing(p -> text(p.get("name")));
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
                text(place.get("tags"))
        ));
        return searchCatalog.matches(merged, q);
    }

    private boolean validCoordinates(Map<String, Object> place) {
        double x = parseDouble(place.get("mapx")), y = parseDouble(place.get("mapy"));
        return x > 124 && x < 132 && y > 32 && y < 39;
    }

    private double distanceKm(Map<String, Object> a, Map<String, Object> b) {
        double lat1 = Math.toRadians(parseDouble(a.get("mapy"))), lat2 = Math.toRadians(parseDouble(b.get("mapy")));
        double lat = lat2 - lat1, lon = Math.toRadians(parseDouble(b.get("mapx")) - parseDouble(a.get("mapx")));
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(lon / 2), 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private void createPlaceFromSubmission(Map<String, Object> sub) {
        String name = text(sub.get("name"));
        String address = text(sub.get("address"));
        String source = "submission:" + sub.get("id");
        if (count("SELECT COUNT(*) FROM places WHERE source=?", source) > 0) {
            jdbc.update("UPDATE places SET name=?,address=?,region=?,category=?,category_label=?,tags=?,approved=TRUE WHERE source=?",
                    name, address, text(sub.get("region")), textOr(sub.get("category"), "tour"), textOr(sub.get("categoryLabel"), "관광지"), text(sub.get("description")), source);
            placeQueries.indexSource(source);
            return;
        }
        if (count("SELECT COUNT(*) FROM places WHERE name=? AND address=?", name, address) > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO places(name,address,region,category,category_label,hidden_score,level,concentration,image,tags,approved,source)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, name, address, text(sub.get("region")), textOr(sub.get("category"), "tour"),
                textOr(sub.get("categoryLabel"), "관광지"), "70", "확인 필요", "확인 필요", "",
                textOr(sub.get("description"), "사용자 제보"), true, source);
        placeQueries.indexSource(source);
    }

    private void saveSearchLog(String userId, String keyword) {
        if (!keyword.isBlank()) {
            jdbc.update("INSERT INTO search_logs(keyword,searched_at,user_id) VALUES(?,?,?)", keyword, LocalDateTime.now(), userId);
        }
    }

    private void ensurePlace(long id) {
        if (count("SELECT COUNT(*) FROM places WHERE id=? AND approved=TRUE AND tour_visible=TRUE", id) == 0) {
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
                """, userId, fullName, userId, email, phone, false, false, true, true, true, userId);
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
        if (!auth.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정으로 로그인해주세요.");
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
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw badRequest("비밀번호는 UTF-8 기준 72바이트까지 사용할 수 있습니다.");
        return passwordEncoder.encode(password);
    }

    private boolean verifyPassword(String password, String stored) {
        try {
            if (stored.startsWith("$2")) return passwordEncoder.matches(password, stored);
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
        jdbc.execute(sql);
    }

    private void ensureColumn(String table, String column, String type) {
        boolean exists = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
                return result.next();
            }
        }));
        if (!exists) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
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
        return auth.requireUser();
    }

    private void requireOwned(String table, long id, String userId) {
        if (count("SELECT COUNT(*) FROM " + table + " WHERE id=? AND user_id=?", id, userId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없습니다.");
    }

    private void validateImage(String image) {
        if (image.length() > 2_000_000) throw badRequest("사진 용량이 너무 큽니다. 작은 사진을 선택해주세요.");
        if (image.startsWith("data:") && !image.matches("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$")) throw badRequest("JPG, PNG, WebP 사진을 선택해주세요.");
        if (image.length() > 20 && !image.startsWith("https://") && !image.startsWith("data:image/")) throw badRequest("사진 형식을 확인해주세요.");
    }

    private void validateInquiry(Map<String, Object> body) {
        DiaryProtection.field(body,"title","",150);
        DiaryProtection.field(body,"body","",4000);
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

}
