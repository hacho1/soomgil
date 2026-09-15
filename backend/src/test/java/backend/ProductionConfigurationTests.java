package backend;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTests {
    MockEnvironment valid() {
        return new MockEnvironment().withProperty("soomgil.public-origin", "https://travel.example.com")
                .withProperty("soomgil.allowed-origins", "https://travel.example.com")
                .withProperty("server.servlet.session.cookie.secure", "true").withProperty("soomgil.demo-users", "false")
                .withProperty("server.address", "127.0.0.1").withProperty("soomgil.admin.username", "operator");
    }
    @Test void validPrivateBackendAndHttpsOriginPass() { assertThatCode(() -> ProductionConfiguration.validate(valid())).doesNotThrowAnyException(); }
    @Test void unsafeProductionSettingsFailBeforeBeanInitialization() {
        for (String origin : new String[]{"", "http://travel.example.com", "https://travel.example.com/path", "https://user:password@travel.example.com", "https://travel.example.com?q=1"}) {
            assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("soomgil.public-origin", origin))).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("server.servlet.session.cookie.secure", "false"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("soomgil.demo-users", "true"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("server.address", "0.0.0.0"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("soomgil.allowed-origins", "*"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("soomgil.admin.username", ""))).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionNaverCallbackMustMatchThePublicOriginAndProxyPort() {
        var configured=valid().withProperty("soomgil.naver.client-id","public-id").withProperty("soomgil.naver.client-secret","test-secret");
        for (String callback : new String[]{"", "http://127.0.0.1:5173/api/auth/naver/callback", "https://other.example.com/api/auth/naver/callback"})
            assertThatThrownBy(() -> ProductionConfiguration.validate(configured.withProperty("soomgil.naver.redirect-uri",callback))).isInstanceOf(IllegalStateException.class);
        configured.withProperty("soomgil.naver.redirect-uri","https://travel.example.com/api/auth/naver/callback");
        assertThatCode(() -> ProductionConfiguration.validate(configured)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductionConfiguration.validate(configured.withProperty("soomgil.naver.client-secret",""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionConfiguration.validate(valid().withProperty("server.port","8081"))).isInstanceOf(IllegalStateException.class);
    }
}
