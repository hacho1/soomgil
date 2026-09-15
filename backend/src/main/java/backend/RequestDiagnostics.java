package backend;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestDiagnostics extends OncePerRequestFilter {
    static final String HEADER = "X-Request-ID";
    static final String ATTRIBUTE = RequestDiagnostics.class.getName() + ".id";
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // Never trust an incoming request ID or record raw URLs, query strings, bodies or credentials.
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE,id); response.setHeader(HEADER,id);
        long started = System.nanoTime();
        Throwable failure = null;
        try { chain.doFilter(request,response); }
        catch (ServletException | IOException | RuntimeException exception) { failure = exception; throw exception; }
        finally {
            long millis = (System.nanoTime()-started)/1_000_000;
            if (failure != null || response.getStatus() >= 500 || millis >= 1500) {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                LoggerFactory.getLogger(RequestDiagnostics.class).warn("api_request id={} route={} status={} durationMs={} failure={}",
                        id, route == null ? "unmatched" : route, failure == null ? response.getStatus() : 500, millis,
                        failure == null ? "none" : failure.getClass().getSimpleName());
            }
        }
    }
}
