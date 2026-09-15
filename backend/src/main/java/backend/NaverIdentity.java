package backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class NaverIdentity {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NaverIdentity(@Value("${soomgil.naver.client-id:}") String clientId,
                         @Value("${soomgil.naver.client-secret:}") String clientSecret,
                         @Value("${soomgil.naver.redirect-uri:}") String redirectUri, ObjectMapper mapper) {
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.redirectUri = redirectUri.trim();
        this.mapper = mapper;
    }

    public boolean available() {
        if (clientId.isBlank() || clientSecret.isBlank()) return false;
        try {
            var uri = URI.create(redirectUri);
            boolean transport = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) &&
                    ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
            return transport && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && "/api/auth/naver/callback".equals(uri.getPath());
        } catch (IllegalArgumentException ignored) { return false; }
    }

    public String origin() {
        var uri = URI.create(redirectUri);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://nid.naver.com/oauth2.0/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("state", state).build().encode().toUriString();
    }

    public record Profile(String id, String email, String nickname, String image) {}

    public Profile exchange(String code, String state) {
        String form = "grant_type=authorization_code&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code) + "&state=" + encode(state) + "&redirect_uri=" + encode(redirectUri);
        var token = send(HttpRequest.newBuilder(URI.create("https://nid.naver.com/oauth2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).timeout(Duration.ofSeconds(8)).build());
        String access = token.path("access_token").asText("");
        if (access.isBlank() || access.length() > 8192 || token.has("error")) throw unavailable();
        var response = send(HttpRequest.newBuilder(URI.create("https://openapi.naver.com/v1/nid/me"))
                .header("Authorization", "Bearer " + access).timeout(Duration.ofSeconds(8)).GET().build());
        return parseProfile(response);
    }

    static Profile parseProfile(JsonNode response) {
        JsonNode profile = response.path("response");
        String id = profile.path("id").asText("");
        if (!"00".equals(response.path("resultcode").asText()) || id.isBlank() || id.length() > 255) throw unavailable();
        return new Profile(id, profile.path("email").asText(""), profile.path("nickname").asText(""), safeImage(profile.path("profile_image").asText("")));
    }

    private JsonNode send(HttpRequest request) {
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = pending.get(8, java.util.concurrent.TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body().length() > 65536) throw unavailable();
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception exception) { pending.cancel(true); throw unavailable(); }
    }

    private static String safeImage(String value) {
        try {
            var uri = URI.create(value);
            String host = uri.getHost();
            return value.length() <= 2000 && "https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null
                    && (host.endsWith(".pstatic.net") || host.equals("pstatic.net")) ? value : "";
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "네이버 인증을 확인하지 못했어요. 잠시 후 다시 시도해주세요.");
    }
}
