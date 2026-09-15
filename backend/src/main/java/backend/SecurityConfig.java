package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${soomgil.sharing.enabled:false}") boolean sharing) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName(sharing ? "SOOMGIL_SHARE_SESSION" : "SOOMGIL_SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        serializer.setRememberMeRequestAttribute("soomgil.remember");
        return serializer;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper,
            HttpSessionSecurityContextRepository repository,
            @Value("${soomgil.allowed-origins}") String allowedOrigins,
            @Value("${soomgil.sharing.enabled:false}") boolean sharing) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http
                .cors(config -> config.configurationSource(source))
                .csrf(config -> config.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(config -> config.securityContextRepository(repository))
                .authorizeHttpRequests(auth -> {
                    if (sharing) {
                        auth.requestMatchers("/api/admin/**","/admin.html","/admin/**").denyAll();
                        auth.requestMatchers(HttpMethod.GET,SharingConfiguration.PAGES).permitAll();
                        auth.requestMatchers(HttpMethod.GET,"/index.html","/assets/**","/licenses/**").permitAll();
                    }
                    auth.requestMatchers("/error", "/api/health", "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/places/**", "/api/suggestions", "/api/suggestions/**", "/api/notices/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated();
                })
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getWriter(), Map.of("message", "로그인이 필요합니다."));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getWriter(), Map.of("message", "요청 권한이 없거나 인증이 만료되었습니다. 새로고침 후 다시 시도해주세요."));
                        }))
                .requestCache(config -> config.disable())
                .logout(config -> config.disable())
                .build();
    }
}
