package backend;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ApiUsageLimits implements WebMvcConfigurer, HandlerInterceptor {
    record Rule(String scope,int maximum,int minutes) {}
    private final AuthRateLimit limits;
    private final SessionAuth auth;
    public ApiUsageLimits(AuthRateLimit limits,SessionAuth auth) { this.limits=limits; this.auth=auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**").order(10); }
    static Rule rule(String method,String route) {
        if (method.equals("GET")) {
            if (Set.of("/api/suggestions","/api/suggestions/related").contains(route)) return new Rule("suggest",240,1);
            if (Set.of("/api/places","/api/places/search","/api/places/nearby","/api/places/discover","/api/places/home","/api/places/recommendations").contains(route)) return new Rule("search",120,1);
            if (Set.of("/api/places/{id}","/api/places/{id}/crowd-info","/api/places/{id}/visit-info","/api/places/{id}/reviews").contains(route)) return new Rule("place-read",240,1);
            if (Set.of("/api/users/{userId}/diaries","/api/users/{userId}/inquiries").contains(route)) return new Rule("records-read",60,1);
        }
        if (Set.of("POST","PUT").contains(method)) {
            if (Set.of("/api/users/{userId}/diaries","/api/users/{userId}/diaries/{id}").contains(route)) return new Rule("diary-write",30,10);
            if (Set.of("/api/users/{userId}/inquiries","/api/users/{userId}/inquiries/{id}").contains(route)) return new Rule("inquiry-write",5,10);
            if (route.equals("/api/places/{id}/reviews")) return new Rule("review-write",10,10);
            if (route.equals("/api/reviews/{id}/like")) return new Rule("review-like",60,1);
        }
        if (method.equals("DELETE") && Set.of("/api/users/{userId}/diaries/{id}","/api/users/{userId}/inquiries/{id}","/api/reviews/{id}").contains(route)) return new Rule("records-delete",60,10);
        return null;
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Rule rule=pattern == null ? null : rule(request.getMethod(),pattern.toString());
        if (rule == null) return true;
        String user=auth.currentUser();
        // An IP budget also covers rotating accounts, with headroom for shared school networks.
        limits.take("api-ip-"+rule.scope(),AuthRateLimit.client(request),rule.maximum()*5,Duration.ofMinutes(rule.minutes()));
        String actor=user.isBlank() ? "guest:"+AuthRateLimit.client(request) : "user:"+user;
        limits.take("api-"+rule.scope(),actor,rule.maximum(),Duration.ofMinutes(rule.minutes()));
        return true;
    }
}
