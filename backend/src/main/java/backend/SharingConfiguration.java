package backend;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@Profile("sharing")
public class SharingConfiguration implements WebMvcConfigurer {
    static final String[] PAGES={"/","/welcome","/onboarding","/category","/search","/map","/mypage","/mypage/**","/settings","/settings/**","/about","/notices","/notices/*","/notifications","/loginform","/signupform","/signup","/recovery","/diary","/diary/**","/places/*"};
    @Bean static BeanFactoryPostProcessor sharingPreflight(Environment env) {
        return factory -> {
            validate(env);
            if (!Files.isRegularFile(Path.of("../frontend/dist/index.html"))) throw new IllegalStateException("Build frontend/dist before temporary sharing");
        };
    }
    static void validate(Environment env) {
        String supplied=env.getProperty("soomgil.public-origin","");
        URI origin;
        try { origin=URI.create(supplied); } catch (IllegalArgumentException failure) { throw new IllegalStateException("Set the exact HTTPS sharing origin"); }
        if (!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null || !origin.getPath().isEmpty() || origin.getQuery()!=null || origin.getFragment()!=null)
            throw new IllegalStateException("Set the exact HTTPS sharing origin without a path");
        if (!supplied.equals(env.getProperty("soomgil.allowed-origins")) || !"127.0.0.1".equals(env.getProperty("server.address"))
                || !env.getProperty("server.servlet.session.cookie.secure",Boolean.class,false) || env.getProperty("soomgil.demo-users",Boolean.class,true)
                || !env.getProperty("soomgil.sharing.enabled",Boolean.class,false)) throw new IllegalStateException("Sharing requires exact CORS, loopback, Secure cookies and disabled demo accounts");
        if (!env.getProperty("soomgil.google.client-id","").isBlank() || !env.getProperty("soomgil.naver.client-id","").isBlank())
            throw new IllegalStateException("Temporary sharing uses email login; configure social login separately for a registered domain");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        for (String route:PAGES) registry.addViewController(route).setViewName("forward:/index.html");
    }
}
