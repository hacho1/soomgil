package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class NaverIdentityTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void configurationOnlyAllowsExplicitSecureOrLoopbackCallback() {
        for (String invalid : new String[]{"", "http://example.com/api/auth/naver/callback", "https://example.com/wrong", "https://user@example.com/api/auth/naver/callback", "https://example.com/api/auth/naver/callback?next=other"})
            assertThat(new NaverIdentity("client", "secret", invalid, mapper).available()).isFalse();
        var identity = new NaverIdentity("client", "secret", "http://127.0.0.1:5173/api/auth/naver/callback", mapper);
        assertThat(identity.available()).isTrue();
        assertThat(identity.authorizeUrl("state-value")).contains("client_id=client", "state=state-value", "redirect_uri=").doesNotContain("secret");
        assertThat(new NaverIdentity("client", "", "http://127.0.0.1:5173/api/auth/naver/callback", mapper).available()).isFalse();
    }

    @Test
    void profileRequiresProviderIdAndRejectsUntrustedPhotoUrls() throws Exception {
        assertThatThrownBy(() -> NaverIdentity.parseProfile(mapper.readTree("{\"resultcode\":\"00\",\"response\":{}}"))).isInstanceOf(ResponseStatusException.class);
        var profile = NaverIdentity.parseProfile(mapper.readTree("{\"resultcode\":\"00\",\"response\":{\"id\":\"opaque-id\",\"profile_image\":\"https://example.com/track\"}}"));
        assertThat(profile.id()).isEqualTo("opaque-id");
        assertThat(profile.email()).isEmpty();
        assertThat(profile.image()).isEmpty();
        assertThat(NaverIdentity.parseProfile(mapper.readTree("{\"resultcode\":\"00\",\"response\":{\"id\":\"opaque-id\",\"profile_image\":\"https://phinf.pstatic.net/profile.jpg\"}}")).image()).isEqualTo("https://phinf.pstatic.net/profile.jpg");
    }
}
