package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:naver-test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class NaverAuthTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NaverIdentity identity;

    @BeforeEach
    void provider() {
        when(identity.available()).thenReturn(true);
        when(identity.origin()).thenReturn("http://127.0.0.1:5173");
        when(identity.authorizeUrl(anyString())).thenAnswer(call -> "https://nid.naver.com/oauth2.0/authorize?state=" + call.getArgument(0));
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;

        HttpResponse<String> call(String method, String path, Object body, boolean token) throws Exception {
            if (token && csrf == null) csrf = json(call("GET", "/api/auth/csrf", null, false)).path("token").asText();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
            if (token) request.header("X-CSRF-TOKEN", csrf);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception { return call("GET", path, null, false); }
        HttpResponse<String> post(String path, Object body) throws Exception { return call("POST", path, body, true); }
        String start(boolean remember) throws Exception {
            var response = post("/api/auth/naver/start", Map.of("autoLogin", remember));
            assertThat(response.statusCode()).isEqualTo(200);
            return URI.create(json(response).path("url").asText()).getQuery().substring("state=".length());
        }
        HttpResponse<String> callback(String state) throws Exception { return get("/api/auth/naver/callback?code=test-code&state=" + state); }
        HttpResponse<String> complete() throws Exception { return post("/api/auth/naver/complete", Map.of("privacyConsent", true, "nickname", "여행친구")); }
    }

    JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    NaverIdentity.Profile profile() {
        String id = UUID.randomUUID().toString();
        return new NaverIdentity.Profile(id, id + "@example.test", "여행친구", "");
    }
    void returns(NaverIdentity.Profile profile) { doReturn(profile).when(identity).exchange(anyString(), anyString()); }

    @Test
    void stateIsSessionBoundSingleUseAndStartRequiresCsrf() throws Exception {
        var one = new Browser(); var other = new Browser(); returns(profile());
        assertThat(one.call("POST", "/api/auth/naver/start", Map.of(), false).statusCode()).isEqualTo(403);
        String state = one.start(false);
        assertThat(other.callback(state).headers().firstValue("location").orElseThrow()).endsWith("?error=expired");
        verify(identity, never()).exchange(anyString(), anyString());
        assertThat(one.callback(state).statusCode()).isEqualTo(303);
        assertThat(one.callback(state).headers().firstValue("location").orElseThrow()).endsWith("?error=expired");
        verify(identity, times(1)).exchange(anyString(), eq(state));
    }

    @Test
    void invalidStateAndDeniedConsentNeverCreateOrAuthenticateAccounts() throws Exception {
        var browser = new Browser(); browser.start(false);
        assertThat(browser.callback("wrong").headers().firstValue("location").orElseThrow()).endsWith("?error=expired");
        String state = browser.start(false);
        assertThat(browser.get("/api/auth/naver/callback?error=access_denied&state=" + state).headers().firstValue("location").orElseThrow()).endsWith("?error=cancelled");
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        verify(identity, never()).exchange(anyString(), anyString());
    }

    @Test
    void newNaverAccountRequiresConsentAndPersistsWithoutPasswordOrFakePhone() throws Exception {
        var browser = new Browser(); var profile = profile(); returns(profile);
        browser.callback(browser.start(true));
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(json(browser.get("/api/auth/naver/pending")).path("email").asText()).isEqualTo(profile.email());
        assertThat(browser.post("/api/auth/naver/complete", Map.of("nickname", "여행친구", "privacyConsent", false)).statusCode()).isEqualTo(400);
        assertThat(browser.post("/api/auth/naver/complete", Map.of("nickname", "씨발", "privacyConsent", true)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE provider_id=?", Long.class, profile.id())).isZero();
        var completed = browser.complete();
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(completed.headers().allValues("set-cookie").toString()).containsIgnoringCase("max-age=").containsIgnoringCase("httponly");
        var user = json(browser.get("/api/auth/me")).path("user");
        assertThat(user.path("authProvider").asText()).isEqualTo("naver");
        assertThat(user.path("emailVerified").asBoolean()).isFalse();
        assertThat(user.path("onboardingCompleted").asBoolean()).isFalse();
        assertThat(user.path("phone").asText()).isEmpty();
        assertThat(browser.complete().statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE provider_id=?", Long.class, profile.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone FROM user_accounts WHERE provider_id=?", String.class, profile.id())).isNull();
        assertThat(browser.post("/api/auth/login", Map.of("identifier", profile.email(), "password", "anything")).statusCode()).isEqualTo(401);
    }

    @Test
    void returningAccountUsesProviderIdEvenWhenNaverEmailChanges() throws Exception {
        var first = new Browser(); var profile = profile(); returns(profile);
        first.callback(first.start(false)); first.complete();
        String user = json(first.get("/api/auth/me")).path("user").path("userId").asText();
        returns(new NaverIdentity.Profile(profile.id(), "changed@example.test", "다른별명", ""));
        var again = new Browser(); again.callback(again.start(false));
        assertThat(json(again.get("/api/auth/me")).path("user").path("userId").asText()).isEqualTo(user);
        assertThat(json(again.get("/api/auth/naver/pending")).path("authenticated").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT email FROM user_accounts WHERE username=?", String.class, user)).isEqualTo(profile.email());
    }

    @Test
    void existingEmailNeverSilentlyLinksToNaverAndUnavailableProviderStaysDisabled() throws Exception {
        returns(new NaverIdentity.Profile(UUID.randomUUID().toString(), "test1@soomgil.local", "여행친구", ""));
        var browser = new Browser();
        assertThat(browser.callback(browser.start(false)).headers().firstValue("location").orElseThrow()).endsWith("?error=existing_account");
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        when(identity.available()).thenReturn(false);
        assertThat(browser.post("/api/auth/naver/start", Map.of()).statusCode()).isEqualTo(503);
        assertThat(json(browser.get("/api/auth/config")).path("naverAvailable").asBoolean()).isFalse();
    }

    @Test
    void missingOptionalEmailCanBeEnteredWithoutClaimingVerification() throws Exception {
        returns(new NaverIdentity.Profile(UUID.randomUUID().toString(), "", "", ""));
        var browser = new Browser(); browser.callback(browser.start(false));
        assertThat(browser.complete().statusCode()).isEqualTo(400);
        var result = browser.post("/api/auth/naver/complete", Map.of("nickname", "여행친구", "privacyConsent", true, "email", UUID.randomUUID() + "@example.test"));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json(browser.get("/api/auth/me")).path("user").path("emailVerified").asBoolean()).isFalse();
    }

    @Test
    void providerFailureAndCancelledRegistrationLeaveNoPendingIdentity() throws Exception {
        var browser = new Browser();
        when(identity.exchange(anyString(), anyString())).thenThrow(new IllegalStateException("private-provider-error"));
        var failed = browser.callback(browser.start(false));
        assertThat(failed.headers().firstValue("location").orElseThrow()).endsWith("?error=failed").doesNotContain("private-provider-error");
        returns(profile()); browser.callback(browser.start(false));
        assertThat(browser.call("DELETE", "/api/auth/naver/pending", null, true).statusCode()).isEqualTo(200);
        assertThat(browser.complete().statusCode()).isEqualTo(401);
    }
}
