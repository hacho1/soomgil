package backend;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SharingConfigurationTests {
    MockEnvironment valid() {
        return new MockEnvironment().withProperty("soomgil.public-origin","https://share.example.test")
                .withProperty("soomgil.allowed-origins","https://share.example.test").withProperty("server.address","127.0.0.1")
                .withProperty("server.servlet.session.cookie.secure","true").withProperty("soomgil.demo-users","false")
                .withProperty("soomgil.sharing.enabled","true");
    }
    @Test void permitsOnlyAnExplicitSecureSharingConfiguration() {
        assertThatCode(() -> SharingConfiguration.validate(valid())).doesNotThrowAnyException();
        for (var setting:java.util.Map.of("soomgil.public-origin","http://share.example.test","soomgil.allowed-origins","*",
                "server.address","0.0.0.0","server.servlet.session.cookie.secure","false","soomgil.demo-users","true",
                "soomgil.sharing.enabled","false","soomgil.google.client-id","unregistered","soomgil.naver.client-id","unregistered").entrySet())
            assertThatThrownBy(() -> SharingConfiguration.validate(valid().withProperty(setting.getKey(),setting.getValue()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsOriginPathsCredentialsQueriesAndMissingOrigins() {
        for (String origin:new String[]{"","https://share.example.test/path","https://name:secret@share.example.test","https://share.example.test?q=1","https://share.example.test#fragment"})
            assertThatThrownBy(() -> SharingConfiguration.validate(valid().withProperty("soomgil.public-origin",origin))).isInstanceOf(IllegalStateException.class);
    }
}
