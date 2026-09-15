package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProgressService progressService;
    @Autowired PlaceQueries placeQueries;
    @Autowired AuthRateLimit authLimits;
    @Autowired EmailVerification verification;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean EmailDelivery mail;
    final Map<String, String> codes = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach void emailFixture() {
        jdbc.update("DELETE FROM auth_rate_limits");
        jdbc.update("DELETE FROM email_challenges");
        codes.clear(); when(mail.available()).thenReturn(true);
        doAnswer(call -> { codes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mail).verification(anyString(), anyString(), anyString(), any());
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;

        HttpResponse<String> call(String method, String path, Object body, boolean withCsrf) throws Exception {
            if (withCsrf && csrf == null) csrf = json(call("GET", "/api/auth/csrf", null, false)).path("token").asText();
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
            if (withCsrf) builder.header("X-CSRF-TOKEN", csrf);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception { return call("GET", path, null, false); }
        HttpResponse<String> post(String path, Object body) throws Exception { return call("POST", path, body, true); }
        HttpResponse<String> put(String path, Object body) throws Exception { return call("PUT", path, body, true); }
        HttpResponse<String> delete(String path) throws Exception { return call("DELETE", path, null, true); }

        String proof(String email, String purpose) throws Exception {
            var requested = post("/api/auth/email/request", Map.of("email", email, "purpose", purpose));
            assertThat(requested.statusCode()).as(requested.body()).isEqualTo(200);
            var confirmed = post("/api/auth/email/confirm", Map.of("challengeId", json(requested).path("challengeId").asText(), "code", codes.get(email.toLowerCase())));
            assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
            return json(confirmed).path("proof").asText();
        }

        String signup() throws Exception {
            String id = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String phone = "010" + String.format("%08d", Math.floorMod(id.hashCode(), 100000000));
            var result = post("/api/auth/signup", Map.of("fullName", "테스트", "username", id, "password", "Secure1!pass", "passwordConfirm", "Secure1!pass", "email", id + "@example.test", "phone", phone, "privacyConsent", true, "emailProof", proof(id + "@example.test", "signup")));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            return id;
        }

        void login(String id) throws Exception {
            var response = post("/api/auth/login", Map.of("identifier", id, "password", "Secure1!pass", "autoLogin", true));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    JsonNode json(HttpResponse<String> result) throws Exception { return mapper.readTree(result.body()); }

    @Test void diaryImagesRoundTripAndForgedDrawingOrBadgesNeverReachStorage() throws Exception {
        var browser=new Browser(); String user=browser.signup(); String route="/api/users/"+user+"/diaries";
        String photo=UploadImagesTests.image("jpeg",4,4), drawing=UploadImagesTests.image("png",4,4);
        var body=new java.util.HashMap<String,Object>(Map.of("title","Image test","imageUrl",photo,"drawingImage",drawing));
        var created=browser.post(route,body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var entry=json(browser.get(route)).get(0); long id=entry.path("id").asLong();
        assertThat(entry.path("imageUrl").asText()).isEqualTo(photo);
        assertThat(entry.path("drawingImage").asText()).isEqualTo(drawing);
        assertThat(jdbc.queryForObject("SELECT storage_bytes FROM diary_entries WHERE id=?",Long.class,id)).isPositive();
        body.put("drawingImage","data:image/png;base64,AAAA");
        assertThat(browser.put(route+"/"+id,body).statusCode()).isEqualTo(400);
        body.put("drawingImage",drawing); body.put("badgeStickers","[{\"badgeId\":\"not-earned\"}]");
        assertThat(browser.put(route+"/"+id,body).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT drawing_image FROM diary_entries WHERE id=?",String.class,id)).isEqualTo(drawing);
        assertThat(browser.delete(route+"/"+id).statusCode()).isEqualTo(200);
    }

    @Test void chunkedOversizeGets413AndCannotCreateDiary() throws Exception {
        var browser=new Browser(); String user=browser.signup(); String route="/api/users/"+user+"/diaries";
        browser.get("/api/health");
        String payload="{\"title\":\"Oversize\",\"body\":\""+"x".repeat((int)RequestBodyLimit.MAX_BYTES)+"\"}";
        for (boolean chunked:new boolean[]{false,true}) {
            var publisher=chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))) : HttpRequest.BodyPublishers.ofString(payload);
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+route)).header("Content-Type","application/json")
                    .header("X-CSRF-TOKEN",browser.csrf).POST(publisher).build();
            var response=browser.http.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
            assertThat(json(response).path("message").asText()).contains("용량");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?",Long.class,user)).isZero();
    }

    @Test void writeLimitReturnsRetryAfterWithoutBlockingReadingDeletingOrOtherUsers() throws Exception {
        var browser=new Browser(); String user=browser.signup(); String route="/api/users/"+user+"/diaries";
        assertThat(browser.post(route,Map.of("title","Keep this record")).statusCode()).isEqualTo(200);
        long id=json(browser.get(route)).get(0).path("id").asLong();
        for(int i=1;i<30;i++) authLimits.take("api-diary-write","user:"+user,30,java.time.Duration.ofMinutes(10));
        var denied=browser.post(route,Map.of("title","Retry later"));
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(json(denied).path("retryAfterSeconds").asInt()).isBetween(1,600);
        assertThat(browser.get(route).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?",Long.class,user)).isEqualTo(1);
        assertThat(browser.delete(route+"/"+id).statusCode()).isEqualTo(200);
        var other=new Browser(); String otherUser=other.signup();
        assertThat(other.post("/api/users/"+otherUser+"/diaries",Map.of("title","Independent allowance")).statusCode()).isEqualTo(200);
        String inquiries="/api/users/"+user+"/inquiries";
        assertThat(browser.post(inquiries,Map.of("title","x".repeat(151),"body","test")).statusCode()).isEqualTo(400);
        assertThat(browser.post(inquiries,Map.of("title","test","body","x".repeat(4001))).statusCode()).isEqualTo(400);
        for(int i=2;i<5;i++) authLimits.take("api-inquiry-write","user:"+user,5,java.time.Duration.ofMinutes(10));
        assertThat(browser.post(inquiries,Map.of("title","test","body","test")).statusCode()).isEqualTo(429);
    }

    @Test void searchLimitCoversAliasesButKeepsAutocompleteAndHealthAvailable() throws Exception {
        var browser=new Browser(); String user=browser.signup();
        for(int i=0;i<120;i++) authLimits.take("api-search","user:"+user,120,java.time.Duration.ofMinutes(1));
        assertThat(browser.get("/api/places/search?query=cafe").statusCode()).isEqualTo(429);
        assertThat(browser.get("/api/places?query=cafe").statusCode()).isEqualTo(429);
        for(int i=0;i<12;i++) assertThat(browser.get("/api/suggestions?q=cafe").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/health").statusCode()).isEqualTo(200);
    }

    @Test
    void publicCatalogAndPrivateDataHaveDifferentPermissions() throws Exception {
        var guest = new Browser();
        assertThat(guest.get("/api/health").headers().firstValue("X-Request-ID").orElse("")).matches("[0-9a-f-]{36}");
        assertThat(guest.get("/api/places").statusCode()).isEqualTo(200);
        assertThat(guest.get("/api/notices").statusCode()).isEqualTo(200);
        assertThat(guest.get("/api/users/test1/settings").statusCode()).isEqualTo(401);
        assertThat(guest.get("/api/users/test1/diaries").statusCode()).isEqualTo(401);
        assertThat(guest.call("POST", "/api/auth/login", Map.of(), false).statusCode()).isEqualTo(403);
    }

    @Test
    void catalogPagesAreBoundedAndHiddenPlacesStayOutOfPublicResponses() throws Exception {
        var guest = new Browser();
        String marker = "catalog" + UUID.randomUUID().toString().replace("-", "");
        try {
            for (int i = 0; i < 70; i++) jdbc.update("INSERT INTO places(name,address,approved,source,mapx,mapy) VALUES(?,?,TRUE,?,'128.9','37.8')", marker + i, "강원도 강릉시", marker);
            placeQueries.indexSource(marker);
            var first = json(guest.get("/api/places/search?query=" + marker + "&size=6"));
            var second = json(guest.get("/api/places/search?query=" + marker + "&size=6&page=2"));
            assertThat(first.path("total").asInt()).isEqualTo(70);
            assertThat(first.path("items").size()).isEqualTo(6);
            assertThat(first.path("source").asText()).isEqualTo("database");
            assertThat(first.path("items").get(0).path("id")).isNotEqualTo(second.path("items").get(0).path("id"));
            assertThat(json(guest.get("/api/places?query=" + marker)).size()).isEqualTo(60);
            assertThat(guest.get("/api/places/search?size=1000").statusCode()).isEqualTo(400);
            assertThat(guest.get("/api/places/nearby?mapX=NaN&mapY=37.8").statusCode()).isEqualTo(400);
            long id = first.path("items").get(0).path("id").asLong();
            jdbc.update("UPDATE places SET tour_visible=FALSE WHERE id=?", id);
            assertThat(json(guest.get("/api/places/search?query=" + marker)).path("total").asInt()).isEqualTo(69);
            assertThat(guest.get("/api/places/" + id).statusCode()).isEqualTo(404);
            assertThat(guest.get("/api/places/" + id + "/crowd-info").statusCode()).isEqualTo(404);
            assertThat(guest.get("/api/places/" + id + "/visit-info").statusCode()).isEqualTo(404);
        } finally { jdbc.update("DELETE FROM places WHERE source=?", marker); }
    }

    @Test
    void onlyAdministratorsCanReadAndControlSyncAndCsrfIsRequired() throws Exception {
        var guest = new Browser();
        assertThat(guest.get("/api/admin/place-sync").statusCode()).isEqualTo(401);
        var member = new Browser(); String user = member.signup();
        assertThat(member.get("/api/admin/place-sync").statusCode()).isEqualTo(403);
        assertThat(member.post("/api/admin/place-sync", Map.of("action", "pause")).statusCode()).isEqualTo(403);
        var admin = new Browser();
        assertThat(admin.post("/api/auth/login", Map.of("identifier", "test01", "password", "Test01!23")).statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/admin/place-sync").statusCode()).isEqualTo(200);
        assertThat(admin.call("POST", "/api/admin/place-sync", Map.of("action", "pause"), false).statusCode()).isEqualTo(403);
        try {
            assertThat(json(admin.post("/api/admin/place-sync", Map.of("action", "pause"))).path("paused").asBoolean()).isTrue();
            assertThat(admin.post("/api/admin/place-sync", Map.of("action", "delete")).statusCode()).isEqualTo(400);
        } finally { admin.post("/api/admin/place-sync", Map.of("action", "resume")); }
    }
    @Test void publicContentKindsPreserveDetailAccessAndHideUnrelatedStockPhotos() throws Exception {
        String marker="quality"+UUID.randomUUID().toString().replace("-","");
        var guest=new Browser();
        try {
            jdbc.update("INSERT INTO places(name,address,source,approved,tour_content_type,category,image,mapx,mapy) VALUES(?,?,?,TRUE,'25','walk','https://images.unsplash.com/generic.jpg','128.9','37.8')",marker,"강릉",marker);
            placeQueries.indexSource(marker);
            var course=json(guest.get("/api/places/search?query="+marker+"&kind=course"));
            assertThat(course.path("total").asInt()).isEqualTo(1);
            assertThat(course.path("items").get(0).path("categoryLabel").asText()).isEqualTo("여행 코스");
            assertThat(course.path("items").get(0).path("image").asText()).isEmpty();
            assertThat(json(guest.get("/api/places/search?query="+marker+"&kind=place")).path("total").asInt()).isZero();
            assertThat(guest.get("/api/places/search?kind=invalid").statusCode()).isEqualTo(400);
            long id=course.path("items").get(0).path("id").asLong();
            var detail=json(guest.get("/api/places/"+id));
            assertThat(detail.path("contentKind").asText()).isEqualTo("course");
            assertThat(detail.path("nearbyHiddenPlaces").size()).isZero();
            assertThat(detail.path("crowdAvailable").asBoolean()).isFalse();
            jdbc.update("UPDATE places SET approved=FALSE WHERE id=?",id);
            assertThat(json(guest.get("/api/places/search?query="+marker+"&kind=course")).path("total").asInt()).isZero();
            assertThat(guest.get("/api/places/"+id).statusCode()).isEqualTo(404);
        } finally { jdbc.update("DELETE FROM places WHERE source=?",marker); }
    }

    @Test void discoveryFeedUsesTheSessionNotASuppliedUserAndNeverExposesPrivateRankingInputs() throws Exception {
        var guest = new Browser();
        var anonymous = guest.get("/api/places/recommendations?userId=test01");
        assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(200);
        assertThat(json(anonymous).path("items").size()).isBetween(1,4);
        assertThat(json(anonymous).path("personalized").asBoolean()).isFalse();
        assertThat(anonymous.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var member = new Browser(); String user = member.signup();
        member.put("/api/users/"+user+"/preferences", Map.of("interests",java.util.List.of("sea","cafe"),"crowdPreference","quiet","preferredRegions",java.util.List.of("제주"),"onboardingStep",3,"onboardingCompleted",true));
        var feed = json(member.get("/api/places/recommendations"));
        assertThat(feed.path("personalized").asBoolean()).isTrue();
        assertThat(feed.path("method").asText()).isEqualTo(DiscoveryRecommendations.METHOD);
        assertThat(feed.path("items").size()).isBetween(1,4);
        for (var place : feed.path("items")) {
            assertThat(place.path("recommendationReason").asText()).isNotBlank();
            assertThat(place.has("discoveryPriority")).isFalse();
            assertThat(place.has("discovery_priority")).isFalse();
            assertThat(place.has("saved")).isFalse();
        }
        assertThat(feed.toString()).doesNotContain(user,user+"@example.test");
        assertThat(json(guest.get("/api/places/recommendations?userId="+user)).path("personalized").asBoolean()).isFalse();
    }

    @Test void nearbyRecommendationsAndCourseUseTheSameTwoDistinctPlaces() throws Exception {
        var browser = new Browser(); var feed = json(browser.get("/api/places/recommendations"));
        long id = feed.path("items").get(0).path("id").asLong();
        var detail = json(browser.get("/api/places/"+id));
        var nearby = detail.path("nearbyHiddenPlaces"); var course = detail.path("halfDayCourse");
        assertThat(nearby.size()).isLessThanOrEqualTo(2);
        assertThat(course.size()).isEqualTo(nearby.size()+1);
        var ids = new java.util.HashSet<Long>();
        for (int i=0;i<nearby.size();i++) {
            var place = nearby.get(i);
            assertThat(place.path("id").asLong()).isNotEqualTo(id);
            assertThat(ids.add(place.path("id").asLong())).isTrue();
            assertThat(place.path("distanceKm").asDouble()).isBetween(0.0,30.0);
            assertThat(course.get(i+1).path("placeId")).isEqualTo(place.path("id"));
        }
    }

    @Test void operationsBackupIsAdminOnlyAndCannotAcceptAnArbitraryOutputPath() throws Exception {
        var guest = new Browser();
        assertThat(guest.get("/api/admin/operations").statusCode()).isEqualTo(401);
        var member = new Browser(); member.signup();
        assertThat(member.get("/api/admin/operations").statusCode()).isEqualTo(403);
        assertThat(member.post("/api/admin/operations/backup", Map.of()).statusCode()).isEqualTo(403);
        var admin = new Browser();
        assertThat(admin.post("/api/auth/login", Map.of("identifier", "test01", "password", "Test01!23")).statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/admin/operations").statusCode()).isEqualTo(200);
        assertThat(admin.call("POST", "/api/admin/operations/backup", Map.of(), false).statusCode()).isEqualTo(403);
        var unsupported = admin.post("/api/admin/operations/backup", Map.of("directory", "C:/arbitrary"));
        assertThat(unsupported.statusCode()).isEqualTo(409);
        assertThat(json(admin.get("/api/admin/operations")).path("backup").path("supported").asBoolean()).isFalse();
    }

    @Test
    void officialVisitInfoIsPublicButUnapprovedPlacesRemainPrivate() throws Exception {
        var guest = new Browser();
        var places = json(guest.get("/api/places"));
        long id = places.get(0).path("id").asLong();
        var response = guest.get("/api/places/" + id + "/visit-info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("state").asText()).isEqualTo("not-configured");
        assertThat(guest.get("/api/places/9223372036854775807/visit-info").statusCode()).isEqualTo(404);
        jdbc.update("INSERT INTO places(name,address,approved,source) VALUES('private detail test','hidden',FALSE,'submission')");
        long hidden = jdbc.queryForObject("SELECT id FROM places WHERE name='private detail test'", Long.class);
        assertThat(guest.get("/api/places/" + hidden + "/visit-info").statusCode()).isEqualTo(404);
    }

    Map<String, Object> emailSignup(String email) throws Exception {
        return Map.of("email", email, "nickname", "여행친구", "password", "Secure1!pass", "passwordConfirm", "Secure1!pass", "privacyConsent", true, "emailProof", new Browser().proof(email, "signup"));
    }

    @Test
    void minimalEmailSignupDoesNotRequireOrInventPhoneAndName() throws Exception {
        var one = new Browser();
        String email = UUID.randomUUID() + "@example.test";
        var created = one.post("/api/auth/signup", emailSignup(email));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var user = json(created).path("user");
        String id = user.path("userId").asText();
        assertThat(user.path("nickname").asText()).isEqualTo("여행친구");
        assertThat(user.path("onboardingCompleted").asBoolean()).isFalse();
        assertThat(user.path("onboardingStep").asInt()).isZero();
        assertThat(user.path("phone").asText()).isEmpty();
        assertThat(user.path("fullName").asText()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT phone FROM user_accounts WHERE username=?", String.class, id)).isNull();
        assertThat(new Browser().post("/api/auth/signup", emailSignup(UUID.randomUUID() + "@example.test")).statusCode()).isEqualTo(200);
        var second = new Browser();
        assertThat(second.post("/api/auth/login", Map.of("identifier", email.toUpperCase(), "password", "Secure1!pass")).statusCode()).isEqualTo(200);
        assertThat(json(second.get("/api/auth/me")).path("user").path("userId").asText()).isEqualTo(id);
        authLimits.clear("mail-resend", email);
        var duplicate = new Browser().post("/api/auth/signup", emailSignup(email.toUpperCase()));
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(duplicate.body()).contains("이미 가입된 사용자");
        assertThat(user.path("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void preferenceDraftAndCompletionPersistAcrossLogins() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        var path = "/api/users/" + user + "/preferences";
        var draft = Map.of("interests", java.util.List.of("sea", "cafe"), "crowdPreference", "quiet", "preferredRegions", java.util.List.of("강원", "제주"), "onboardingStep", 2, "onboardingCompleted", false);
        assertThat(browser.put(path, draft).statusCode()).isEqualTo(200);
        var again = new Browser(); again.login(user);
        var saved = json(again.get("/api/auth/me")).path("user");
        assertThat(saved.path("onboardingStep").asInt()).isEqualTo(2);
        assertThat(saved.path("crowdPreference").asText()).isEqualTo("quiet");
        assertThat(saved.path("interests").toString()).isEqualTo("[\"sea\",\"cafe\"]");
        assertThat(saved.path("preferredRegions").toString()).isEqualTo("[\"강원\",\"제주\"]");
        var completed = new java.util.LinkedHashMap<String, Object>(draft);
        completed.put("onboardingCompleted", true); completed.put("onboardingStep", 3);
        assertThat(again.put(path, completed).statusCode()).isEqualTo(200);
        assertThat(json(browser.get("/api/auth/me")).path("user").path("onboardingCompleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT onboarding_completed FROM user_preferences WHERE user_id=?", Boolean.class, user)).isTrue();
    }

    @Test
    void invalidPreferencesAndOtherUsersCannotModifySavedChoices() throws Exception {
        var owner = new Browser(); String one = owner.signup();
        var stranger = new Browser(); String two = stranger.signup();
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("interests", java.util.List.of("바다"), "crowdPreference", "normal", "preferredRegions", java.util.List.of(), "onboardingStep", 1, "onboardingCompleted", false));
        String path = "/api/users/" + one + "/preferences";
        assertThat(new Browser().put(path, body).statusCode()).isEqualTo(401);
        assertThat(stranger.put(path, body).statusCode()).isEqualTo(403);
        assertThat(owner.put(path, body).statusCode()).isEqualTo(200);
        body.put("preferredRegions", java.util.List.of("잘못된지역"));
        body.put("interests", java.util.List.of("cafe"));
        assertThat(owner.put(path, body).statusCode()).isEqualTo(400);
        assertThat(json(owner.get("/api/auth/me")).path("user").path("interests").toString()).isEqualTo("[\"sea\"]");
        body.put("preferredRegions", java.util.List.of()); body.put("interests", "sea");
        assertThat(owner.put(path, body).statusCode()).isEqualTo(400);
        body.put("interests", java.util.List.of("sea")); body.put("onboardingCompleted", true);
        assertThat(owner.put(path, body).statusCode()).isEqualTo(400);
        assertThat(owner.put("/api/users/" + one + "/settings", Map.of("interestsJson", "not-json")).statusCode()).isEqualTo(400);
    }

    @Test
    void legacyAccountsAreNotForcedBackIntoOnboarding() throws Exception {
        var browser = new Browser();
        assertThat(browser.post("/api/auth/login", Map.of("identifier", "test1", "password", "Test1!234")).statusCode()).isEqualTo(200);
        assertThat(json(browser.get("/api/auth/me")).path("user").path("onboardingCompleted").asBoolean()).isTrue();
    }

    @Test
    void editingLegacyPreferencesKeepsOnboardingCompleted() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        jdbc.update("DELETE FROM user_preferences WHERE user_id=?", user);
        var draft = Map.of("interests", java.util.List.of("sea"), "crowdPreference", "quiet", "preferredRegions", java.util.List.of("제주"), "onboardingStep", 1, "onboardingCompleted", false);
        assertThat(browser.put("/api/users/" + user + "/preferences", draft).statusCode()).isEqualTo(200);
        assertThat(json(browser.get("/api/auth/me")).path("user").path("onboardingCompleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT onboarding_completed FROM user_preferences WHERE user_id=?", Boolean.class, user)).isTrue();
    }

    @Test
    void signupBlocksReservedAdminNicknameAbuseAndOversizedPassword() throws Exception {
        var browser = new Browser();
        var body = new java.util.LinkedHashMap<String, Object>(emailSignup(UUID.randomUUID() + "@example.test"));
        body.put("username", "test01");
        var reserved = browser.post("/api/auth/signup", body);
        assertThat(reserved.statusCode()).isEqualTo(400);
        assertThat(reserved.body()).contains("사용할 수 없는 아이디");
        body.remove("username"); body.put("nickname", "씨발여행");
        assertThat(browser.post("/api/auth/signup", body).statusCode()).isEqualTo(400);
        body.put("nickname", "정상닉네임");
        String longPassword = "Aa1!" + "가".repeat(25);
        body.put("password", longPassword); body.put("passwordConfirm", longPassword);
        assertThat(browser.post("/api/auth/signup", body).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE email=?", Long.class, body.get("email"))).isZero();
        body.remove("nickname"); body.put("password", "Secure1!pass"); body.put("passwordConfirm", "Secure1!pass");
        assertThat(browser.post("/api/auth/signup", body).statusCode()).isEqualTo(400);
    }

    @Test
    void accountCreationPersistsVerifiedEmailAndBcryptButNotPhoneVerification() throws Exception {
        var first = new Browser(); String user = first.signup();
        var saved = json(first.get("/api/auth/me")).path("user");
        assertThat(saved.path("emailVerified").asBoolean()).isTrue();
        assertThat(saved.path("phoneVerified").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE username=?", String.class, user)).startsWith("$2");
        var second = new Browser(); second.login(user);
        assertThat(json(second.get("/api/auth/me")).path("user").path("userId").asText()).isEqualTo(user);
        assertThat(second.cookies.getCookieStore().getCookies()).anyMatch(cookie -> cookie.getName().equals("SOOMGIL_SESSION") && cookie.isHttpOnly() && cookie.getMaxAge() > 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.class, user)).isGreaterThan(0);
    }

    @Test
    void identityCannotBeChangedBySupplyingAnotherUserId() throws Exception {
        var first = new Browser(); String one = first.signup();
        var second = new Browser(); String two = second.signup();
        assertThat(first.get("/api/users/" + two + "/settings").statusCode()).isEqualTo(403);
        assertThat(first.put("/api/users/" + two + "/settings", Map.of("nickname", "changed")).statusCode()).isEqualTo(403);
        assertThat(first.get("/api/admin/inquiries?adminCode=ADMIN2026").statusCode()).isEqualTo(403);
        long place = json(first.get("/api/places")).get(0).path("id").asLong();
        first.post("/api/places/" + place + "/reviews", Map.of("text", "좋은 여행 장소입니다.", "userId", two));
        var reviews = json(first.get("/api/places/" + place + "/reviews"));
        assertThat(reviews.get(0).path("mine").asBoolean()).isTrue();
        assertThat(reviews.get(0).has("ownerId")).isFalse();
        long review = reviews.get(0).path("id").asLong();
        assertThat(second.delete("/api/reviews/" + review + "?userId=admin").statusCode()).isEqualTo(404);
        assertThat(first.delete("/api/reviews/" + review).statusCode()).isEqualTo(200);
    }

    @Test
    void diaryUpdatesNeverRevealAnotherUsersContent() throws Exception {
        var first = new Browser(); String one = first.signup();
        var second = new Browser(); String two = second.signup();
        var body = Map.of("title", "나의 기록", "body", "개인 기록", "visitedDate", "2026-02-28");
        var result = first.post("/api/users/" + one + "/diaries", body);
        long id = json(result).path("diary").path("id").asLong();
        assertThat(id).isPositive();
        assertThat(second.put("/api/users/" + two + "/diaries/" + id, body).statusCode()).isEqualTo(404);
        assertThat(first.put("/api/users/" + one + "/diaries/" + id, body).statusCode()).isEqualTo(200);
    }

    @Test
    void favoritesAndAwardsAreIdempotentAndPersistAfterRemoval() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        long place = json(browser.get("/api/places")).get(0).path("id").asLong();
        var path = "/api/users/" + user + "/favorites";
        browser.post(path, Map.of("placeId", place)); browser.post(path, Map.of("placeId", place));
        assertThat(json(browser.get(path)).size()).isEqualTo(1);
        browser.get("/api/places/" + place); browser.get("/api/places/" + place);
        long exp = json(browser.get("/api/users/" + user + "/progress")).path("exp").asLong();
        browser.delete(path + "/" + place); browser.post(path, Map.of("placeId", place));
        assertThat(json(browser.get("/api/users/" + user + "/progress")).path("exp").asLong()).isEqualTo(exp);
        assertThat(browser.put("/api/users/" + user + "/settings", Map.of("displayBadgeIdsJson", "[\"community\"]")).statusCode()).isEqualTo(400);
        assertThat(browser.put("/api/users/" + user + "/settings", Map.of("displayBadgeIdsJson", "[]")).statusCode()).isEqualTo(200);
    }

    @Test
    void notificationReadStateIsPerUserIncludingBroadcasts() throws Exception {
        var first = new Browser(); String one = first.signup();
        var second = new Browser(); String two = second.signup();
        jdbc.update("INSERT INTO notifications(user_id,title,message,type,reference_type,read_flag,created_at) VALUES('*','공지','새 소식','notice','notice',FALSE,CURRENT_TIMESTAMP)");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM notifications", Long.class);
        first.put("/api/users/" + one + "/notifications/" + id + "/read", Map.of());
        var firstNotes = json(first.get("/api/users/" + one + "/notifications"));
        var secondNotes = json(second.get("/api/users/" + two + "/notifications"));
        assertThat(firstNotes.findValuesAsText("read")).contains("true");
        assertThat(secondNotes.get(0).path("read").asBoolean()).isFalse();
    }

    @Test
    void accountMutationRequiresPasswordAndLogoutRevokesSession() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        assertThat(browser.put("/api/users/" + user + "/account", Map.of("email", "new@example.test", "currentPassword", "wrong")).statusCode()).isEqualTo(403);
        assertThat(browser.post("/api/auth/reset-password", Map.of("identifier", user, "newPassword", "Stolen1!pass")).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/auth/google", Map.of("email", "victim@example.test")).statusCode()).isIn(400, 401, 503);
        assertThat(browser.post("/api/auth/logout", Map.of()).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void adminNoticeCrudAndSubmissionTransitionsKeepPublishedDataConsistent() throws Exception {
        var author = new Browser(); String user = author.signup();
        var admin = new Browser();
        assertThat(admin.post("/api/auth/login", Map.of("identifier", "test01", "password", "Test01!23")).statusCode()).isEqualTo(200);
        var noticeBody = Map.of("title", "운영 공지", "body", "새로운 소식", "bodySize", "large", "bodyWeight", "bold");
        var created = admin.post("/api/admin/notices", noticeBody);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        long notice = json(created).path("notice").path("id").asLong();
        assertThat(admin.put("/api/admin/notices/" + notice, noticeBody).statusCode()).isEqualTo(200);
        assertThat(admin.delete("/api/admin/notices/" + notice).statusCode()).isEqualTo(200);
        assertThat(author.get("/api/notices/" + notice).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reference_type='notice' AND reference_id=?", Long.class, String.valueOf(notice))).isZero();

        var submitted = author.post("/api/place-submissions", Map.of("name", "제보 " + user, "address", "서울특별시 종로구", "region", "서울", "category", "tour", "description", "제보 내용"));
        long submission = json(submitted).path("submission").path("id").asLong();
        String route = "/api/admin/place-submissions/" + submission;
        assertThat(admin.put(route, Map.of("status", "승인")).statusCode()).isEqualTo(200);
        long place = jdbc.queryForObject("SELECT id FROM places WHERE source=?", Long.class, "submission:" + submission);
        assertThat(admin.put(route, Map.of("name", "수정된 제보 " + user, "address", "서울특별시 종로구", "region", "서울")).statusCode()).isEqualTo(200);
        assertThat(json(author.get("/api/places/" + place)).path("name").asText()).startsWith("수정된 제보");
        author.post("/api/users/" + user + "/favorites", Map.of("placeId", place));
        assertThat(admin.put(route, Map.of("status", "반려")).statusCode()).isEqualTo(200);
        assertThat(author.get("/api/places/" + place).statusCode()).isEqualTo(404);
        assertThat(author.get("/api/places/" + place + "/reviews").statusCode()).isEqualTo(404);
        assertThat(json(author.get("/api/users/" + user + "/favorites")).size()).isZero();
        assertThat(admin.put(route, Map.of("status", "승인")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE source=?", Long.class, "submission:" + submission)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity_logs WHERE user_id=? AND activity_type='submission-approved'", Long.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_badges WHERE user_id=? AND badge_id='approved-first'", Long.class, user)).isEqualTo(1);
    }

    @Test
    void searchSupportsCompoundQueriesAndRecoveredCoordinatesAreReal() throws Exception {
        var browser = new Browser();
        var places = json(browser.get("/api/places?query=" + java.net.URLEncoder.encode("강릉 바다", java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(places.size()).isGreaterThan(0);
        var place = places.get(0);
        assertThat(place.path("ratingVerified").asBoolean()).isFalse();
        assertThat(place.path("crowdVerified").asBoolean()).isFalse();
        assertThat(place.path("imageVerified").asBoolean()).isTrue();
        var detail = json(browser.get("/api/places/" + place.path("id").asLong()));
        assertThat(detail.path("nearbyHiddenPlaces").size()).isLessThanOrEqualTo(2);
        detail.path("nearbyHiddenPlaces").forEach(item -> assertThat(item.path("distanceKm").asDouble()).isLessThanOrEqualTo(30));
    }

    @Test
    void publicCrowdUsesOriginalArchiveAndDoesNotReviveSentinelOrSeedValues() throws Exception {
        var browser = new Browser();
        var expected = Map.of("순포해변", 17.93, "뒷뜨루관광농원", 11.94, "소돌해수욕장", 28.01, "오대산 소금강계곡", 16.40, "묵호등대", 23.27);
        var places = json(browser.get("/api/places"));
        int restored = 0;
        for (var place : places) {
            String name = place.path("name").asText();
            assertThat(place.path("ratingVerified").asBoolean()).isFalse();
            assertThat(place.path("crowdVerified").asBoolean()).isFalse();
            if (expected.containsKey(name)) {
                restored++;
                assertThat(place.path("crowdAvailable").asBoolean()).isTrue();
                assertThat(place.path("crowdValue").asDouble()).isEqualTo(expected.get(name));
                assertThat(place.path("crowd").asText()).isEqualTo("한적함");
                assertThat(place.path("crowdBasis").asText()).isEqualTo("archived");
                assertThat(place.path("crowdSource").asText()).isEqualTo("legacy-db");
                assertThat(place.path("crowdCollectedAt").isNull()).isTrue();
                var detail = json(browser.get("/api/places/" + place.path("id").asLong()));
                assertThat(detail.path("crowdValue")).isEqualTo(place.path("crowdValue"));
                assertThat(detail.path("recommendationReason").asText()).contains("보관 자료");
            } else {
                assertThat(place.path("crowdAvailable").asBoolean()).isFalse();
                assertThat(place.path("crowdValue").isNull()).isTrue();
                assertThat(place.path("crowd").asText()).isEqualTo("확인 필요");
            }
        }
        assertThat(restored).isEqualTo(5);
    }

    @Test
    void discoveryValidatesAreaAndDoesNotFailWithoutUpstreamKey() throws Exception {
        var browser = new Browser();
        for (String query : new String[]{"page=0", "page=10001", "mapX=127", "mapX=NaN&mapY=37", "mapX=127&mapY=90", "radius=20001", "region=invalid"}) {
            assertThat(browser.get("/api/places/discover?" + query).statusCode()).isEqualTo(400);
        }
        var response = browser.get("/api/places/discover");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("state").asText()).isEqualTo("not-configured");
        assertThat(json(response).path("storedTotal").asInt()).isPositive();
    }

    @Test
    void expandedSearchIsPublicAndNeverSuggestsUnapprovedPlaceNames() throws Exception {
        var browser = new Browser();
        String hidden = "private" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO places(name,address,approved) VALUES(?,?,FALSE)", hidden, "서울");
        assertThat(json(browser.get("/api/suggestions?q=" + hidden)).size()).isZero();
        var related = browser.get("/api/suggestions/related?q=" + java.net.URLEncoder.encode("바다", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(related.statusCode()).isEqualTo(200);
        assertThat(json(related).toString()).contains("바다 카페");
        assertThat(browser.get("/api/suggestions?q=" + "a".repeat(101)).statusCode()).isEqualTo(400);
    }

    @Test
    void expandedBadgesReconcileHistoricActivityWithoutDuplicatingRewards() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        for (int index = 0; index < 15; index++) jdbc.update("INSERT INTO user_activity_logs(user_id,activity_type,target_id,exp_amount,created_at) VALUES(?,'view',?,2,CURRENT_TIMESTAMP)", user, "historic" + index);
        jdbc.update("INSERT INTO user_badges(user_id,badge_id,earned_at) VALUES(?,'first-view',CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO user_activity_logs(user_id,activity_type,target_id,exp_amount,created_at) VALUES(?,'badge','first-view',50,CURRENT_TIMESTAMP)", user);
        var first = json(browser.get("/api/users/" + user + "/progress"));
        assertThat(first.path("badges").size()).isEqualTo(24);
        assertThat(first.path("exp").asInt()).isEqualTo(140);
        assertThat(json(browser.get("/api/users/" + user + "/progress"))).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND type='badge'", Long.class, user)).isEqualTo(2);
        String selected = "[\"first-view\",\"hidden-hunter\",\"view-fifteen\"]";
        assertThat(browser.put("/api/users/" + user + "/settings", Map.of("displayBadgeIdsJson", selected)).statusCode()).isEqualTo(200);
        progressService.record(user, "favorite", "one");
        assertThat(browser.put("/api/users/" + user + "/settings", Map.of("displayBadgeIdsJson", "[\"first-view\",\"hidden-hunter\",\"view-fifteen\",\"first-favorite\"]")).statusCode()).isEqualTo(400);
        assertThat(browser.put("/api/users/" + user + "/settings", Map.of("displayBadgeIdsJson", "[]")).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentMilestonesAwardOnceAndNewAccountStartsWithoutBadges() throws Exception {
        var browser = new Browser(); String user = browser.signup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_badges WHERE user_id=?", Long.class, user)).isZero();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var work = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int index = 0; index < 8; index++) work.add(() -> { progressService.record(user, "favorite", "same-place"); return null; });
            for (var result : pool.invokeAll(work)) result.get();
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity_logs WHERE user_id=? AND activity_type='favorite'", Long.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_badges WHERE user_id=?", Long.class, user)).isEqualTo(1);
        assertThat(progressService.summary(user).get("exp")).isEqualTo(15);
    }

    @Test
    void signupFailsClosedWithoutProofOrSmtpAndCodesNeverAppearInResponses() throws Exception {
        var browser = new Browser();
        String email = UUID.randomUUID() + "@example.test";
        var body = Map.of("email", email, "nickname", "여행친구", "password", "Secure1!pass", "passwordConfirm", "Secure1!pass", "privacyConsent", true, "emailVerified", true);
        assertThat(browser.post("/api/auth/signup", body).statusCode()).isEqualTo(400);
        when(mail.available()).thenReturn(false);
        assertThat(json(browser.get("/api/auth/config")).path("emailVerificationAvailable").asBoolean()).isFalse();
        assertThat(browser.post("/api/auth/email/request", Map.of("email", email, "purpose", "signup")).statusCode()).isEqualTo(503);
        verify(mail, never()).verification(anyString(), anyString(), anyString(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE email=?", Integer.class, email)).isZero();
        when(mail.available()).thenReturn(true);
        var response = browser.post("/api/auth/email/request", Map.of("email", email, "purpose", "signup"));
        assertThat(response.body()).doesNotContain(codes.get(email), "code_hash", "proof");
        var row = jdbc.queryForMap("SELECT * FROM email_challenges WHERE email=?", email);
        assertThat(row.get("code_hash").toString()).startsWith("$2").doesNotContain(codes.get(email));
        assertThat(browser.call("POST", "/api/auth/email/confirm", Map.of(), false).statusCode()).isEqualTo(403);
    }

    @Test
    void wrongCodeAttemptsCommitAndResendingInvalidatesPreviousCode() throws Exception {
        var browser = new Browser(); String email = UUID.randomUUID() + "@example.test";
        var body = Map.of("email", email, "purpose", "signup");
        var first = json(browser.post("/api/auth/email/request", body));
        String id = first.path("challengeId").asText(), code = codes.get(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", id, "code", wrong)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_challenges WHERE id=?", Integer.class, id)).isEqualTo(5);
        assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", id, "code", code)).statusCode()).isEqualTo(400);
        var limited = browser.post("/api/auth/email/request", body);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        authLimits.clear("mail-resend", email);
        String second = json(browser.post("/api/auth/email/request", body)).path("challengeId").asText();
        assertThat(second).isNotEqualTo(id);
        assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", id, "code", code)).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", second, "code", codes.get(email))).statusCode()).isEqualTo(200);
    }

    @Test
    void expiredCodesAndProofsAreRejectedAndProofsArePurposeBound() throws Exception {
        var browser = new Browser(); String email = UUID.randomUUID() + "@example.test";
        String id = json(browser.post("/api/auth/email/request", Map.of("email", email, "purpose", "signup"))).path("challengeId").asText();
        jdbc.update("UPDATE email_challenges SET expires_at=0 WHERE id=?", id);
        assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", id, "code", codes.get(email))).statusCode()).isEqualTo(400);
        authLimits.clear("mail-resend", email);
        String proof = browser.proof(email, "signup");
        assertThat(browser.post("/api/auth/find-id", Map.of("email", email, "emailProof", proof)).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/auth/find-id", Map.of("email", "other@example.test", "emailProof", proof)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT consumed FROM email_challenges WHERE proof_hash=?", Boolean.class, AuthRateLimit.digest(proof))).isFalse();
        jdbc.update("UPDATE email_challenges SET proof_expires=0 WHERE proof_hash=?", AuthRateLimit.digest(proof));
        var signup = Map.of("email", email, "emailProof", proof, "nickname", "여행친구", "password", "Secure1!pass", "passwordConfirm", "Secure1!pass", "privacyConsent", true);
        assertThat(browser.post("/api/auth/signup", signup).statusCode()).isEqualTo(400);
    }

    @Test
    void proofIsSingleUseEvenWithConcurrentRequestsAndRollbackRestoresIt() throws Exception {
        var browser = new Browser(); String email = UUID.randomUUID() + "@example.test";
        String proof = browser.proof(email, "signup");
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(status -> {
            verification.consume(proof, email, "signup", ""); throw new IllegalStateException("simulated write failure");
        })).isInstanceOf(IllegalStateException.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var work = java.util.List.<java.util.concurrent.Callable<Boolean>>of(
                    () -> consumeOnce(tx, proof, email), () -> consumeOnce(tx, proof, email));
            var results = pool.invokeAll(work);
            assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
        } finally { pool.shutdownNow(); }
    }
    boolean consumeOnce(org.springframework.transaction.support.TransactionTemplate tx, String proof, String email) {
        try { tx.executeWithoutResult(status -> verification.consume(proof, email, "signup", "")); return true; }
        catch (org.springframework.web.server.ResponseStatusException failure) { return false; }
    }

    @Test
    void resetPasswordRequiresOwnershipAndRevokesEverySessionWithoutAutoLogin() throws Exception {
        var first = new Browser(); String user = first.signup(), email = user + "@example.test";
        var second = new Browser(); second.login(user);
        var recovery = new Browser(); authLimits.clear("mail-resend", email);
        String proof = recovery.proof(email, "reset-password");
        var body = Map.of("email", email, "emailProof", proof, "newPassword", "Changed1!pass", "passwordConfirm", "Changed1!pass");
        assertThat(recovery.post("/api/auth/reset-password", body).statusCode()).isEqualTo(200);
        assertThat(first.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(second.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(recovery.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(recovery.post("/api/auth/reset-password", body).statusCode()).isEqualTo(400);
        assertThat(recovery.post("/api/auth/login", Map.of("identifier", email, "password", "Secure1!pass")).statusCode()).isEqualTo(401);
        assertThat(recovery.post("/api/auth/login", Map.of("identifier", email, "password", "Changed1!pass")).statusCode()).isEqualTo(200);
        verify(mail).passwordChanged(email);
    }

    @Test
    void recoveryDoesNotRevealUnknownOrUnverifiedEmailsAndCannotResetSocialPasswords() throws Exception {
        var browser = new Browser(); String user = browser.signup(), email = user + "@example.test";
        jdbc.update("UPDATE user_accounts SET email_verified=FALSE WHERE username=?", user);
        codes.clear(); authLimits.clear("mail-resend", email);
        var existing = json(browser.post("/api/auth/email/request", Map.of("email", email, "purpose", "reset-password")));
        String unknown = UUID.randomUUID() + "@example.test";
        var missing = json(browser.post("/api/auth/email/request", Map.of("email", unknown, "purpose", "reset-password")));
        assertThat(existing.path("message")).isEqualTo(missing.path("message"));
        assertThat(existing.path("expiresIn")).isEqualTo(missing.path("expiresIn"));
        assertThat(codes).isEmpty();
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE username=?", String.class, user);
        jdbc.update("UPDATE user_accounts SET email_verified=TRUE,auth_provider='google' WHERE username=?", user);
        authLimits.clear("mail-resend", email);
        String proof = browser.proof(email, "reset-password");
        var denied = browser.post("/api/auth/reset-password", Map.of("email", email, "emailProof", proof, "newPassword", "Changed1!pass", "passwordConfirm", "Changed1!pass"));
        assertThat(denied.statusCode()).isEqualTo(400);
        assertThat(denied.body()).contains("소셜 로그인");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE username=?", String.class, user)).isEqualTo(oldHash);
    }

    @Test
    void findIdRequiresProofAndOldRecoveryProofStopsWorkingAfterEmailChange() throws Exception {
        var browser = new Browser(); String user = browser.signup(), email = user + "@example.test";
        var guest = new Browser(); authLimits.clear("mail-resend", email);
        String proof = guest.proof(email, "find-id");
        assertThat(json(guest.post("/api/auth/find-id", Map.of("email", email, "emailProof", proof))).path("username").asText()).isEqualTo(user);
        assertThat(guest.post("/api/auth/find-id", Map.of("email", email, "emailProof", proof)).statusCode()).isEqualTo(400);
        authLimits.clear("mail-resend", email); String oldRecovery = guest.proof(email, "reset-password");
        String nextEmail = UUID.randomUUID() + "@example.test";
        assertThat(browser.put("/api/users/" + user + "/account", Map.of("email", nextEmail, "currentPassword", "Secure1!pass")).statusCode()).isEqualTo(400);
        String nextProof = browser.proof(nextEmail, "change-email");
        assertThat(browser.put("/api/users/" + user + "/account", Map.of("email", nextEmail, "currentPassword", "Secure1!pass", "emailProof", nextProof)).statusCode()).isEqualTo(200);
        assertThat(guest.post("/api/auth/reset-password", Map.of("email", email, "emailProof", oldRecovery, "newPassword", "Changed1!pass", "passwordConfirm", "Changed1!pass")).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM user_accounts WHERE username=?", Boolean.class, user)).isTrue();
    }

    @Test
    void currentEmailVerificationRequiresTheSameSignedInAccount() throws Exception {
        var one = new Browser(); String first = one.signup(), email = first + "@example.test";
        var two = new Browser(); String second = two.signup();
        jdbc.update("UPDATE user_accounts SET email_verified=FALSE WHERE username=?", first);
        authLimits.clear("mail-resend", email);
        String proof = one.proof(email, "verify-email");
        assertThat(two.post("/api/auth/verify-email", Map.of("email", email, "emailProof", proof)).statusCode()).isEqualTo(400);
        assertThat(one.post("/api/auth/verify-email", Map.of("email", email, "emailProof", proof)).statusCode()).isEqualTo(200);
        assertThat(json(one.get("/api/auth/me")).path("user").path("emailVerified").asBoolean()).isTrue();
        assertThat(new Browser().post("/api/auth/email/request", Map.of("email", email, "purpose", "change-email")).statusCode()).isEqualTo(401);
    }

    @Test
    void loginAliasesShareAttemptLimitAndSuccessClearsAccountCounter() throws Exception {
        var browser = new Browser(); String user = browser.signup(); var guest = new Browser();
        for (int i = 0; i < 4; i++) assertThat(guest.post("/api/auth/login", Map.of("identifier", i % 2 == 0 ? user : user + "@example.test", "password", "wrong")).statusCode()).isEqualTo(401);
        guest.login(user);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits WHERE bucket_hash=?", Integer.class, AuthRateLimit.digest("login-account\n" + user))).isZero();
        for (int i = 0; i < 5; i++) assertThat(guest.post("/api/auth/login", Map.of("identifier", i % 2 == 0 ? user : user + "@example.test", "password", "wrong")).statusCode()).isEqualTo(401);
        var blocked = guest.post("/api/auth/login", Map.of("identifier", user, "password", "Secure1!pass"));
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(json(blocked).path("retryAfterSeconds").asInt()).isBetween(1, 900);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void failedMailDeliveryInvalidatesCodeInsteadOfAcceptingUndeliveredCode() throws Exception {
        doAnswer(call -> { codes.put(call.getArgument(0), call.getArgument(1)); ((Runnable) call.getArgument(3)).run(); return null; })
                .when(mail).verification(anyString(), anyString(), anyString(), any());
        var browser = new Browser(); String email = UUID.randomUUID() + "@example.test";
        String id = json(browser.post("/api/auth/email/request", Map.of("email", email, "purpose", "signup"))).path("challengeId").asText();
        assertThat(browser.post("/api/auth/email/confirm", Map.of("challengeId", id, "code", codes.get(email))).statusCode()).isEqualTo(400);
    }
}
