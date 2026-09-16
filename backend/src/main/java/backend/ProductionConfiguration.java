package backend;

import java.net.URI;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@Profile("production")
public class ProductionConfiguration {
    @Bean static BeanFactoryPostProcessor productionPreflight(Environment environment) {
        return factory -> validate(environment);
    }
    static void validate(Environment env) {
        String supplied = env.getProperty("soomgil.public-origin", "");
        URI origin;
        try { origin = URI.create(supplied); } catch (IllegalArgumentException failure) { throw new IllegalStateException("Set SOOMGIL_PUBLIC_ORIGIN to the HTTPS service origin"); }
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty())
            throw new IllegalStateException("Set SOOMGIL_PUBLIC_ORIGIN to an HTTPS origin without a path");
        if (!supplied.equals(env.getProperty("soomgil.allowed-origins"))) throw new IllegalStateException("Production CORS must match SOOMGIL_PUBLIC_ORIGIN exactly");
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) throw new IllegalStateException("Production session cookies must be Secure");
        if (env.getProperty("soomgil.demo-users", Boolean.class, false)) throw new IllegalStateException("Disable SOOMGIL_DEMO_USERS before production");
        if (!"127.0.0.1".equals(env.getProperty("server.address"))) throw new IllegalStateException("Production backend must bind to loopback behind the local HTTPS proxy");
        if (env.getProperty("soomgil.admin.username", "").isBlank()) throw new IllegalStateException("Set a production administrator account");
        if (SessionAuth.demoUsername(env.getProperty("soomgil.admin.username"))) throw new IllegalStateException("Use a private administrator account, not a demo username");
        if (env.getProperty("server.port", Integer.class, 8080) != 8080)
            throw new IllegalStateException("The bundled HTTPS proxy requires backend port 8080");
        String naverId = env.getProperty("soomgil.naver.client-id", "").trim();
        String naverSecret = env.getProperty("soomgil.naver.client-secret", "").trim();
        if (!naverId.isEmpty() || !naverSecret.isEmpty()) {
            if (naverId.isEmpty() || naverSecret.isEmpty()) throw new IllegalStateException("Configure both Naver credentials or disable Naver login");
            if (!(supplied + "/api/auth/naver/callback").equals(env.getProperty("soomgil.naver.redirect-uri", "")))
                throw new IllegalStateException("Production Naver callback must use SOOMGIL_PUBLIC_ORIGIN/api/auth/naver/callback");
        }
    }
}
