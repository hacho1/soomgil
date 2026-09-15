package backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;

class RequestDiagnosticsTests {
    @Test void failureLogsUseGeneratedIdsAndRouteTemplatesWithoutPrivateInput() throws Exception {
        var logger=(Logger)LoggerFactory.getLogger(RequestDiagnostics.class);
        var events=new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events);
        try {
            var request=new MockHttpServletRequest("GET","/api/users/private-person/settings");
            request.setQueryString("token=private-token"); request.addHeader("X-Request-ID","untrusted-id");
            request.addHeader("Authorization","private-authorization");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/users/{userId}/settings");
            var response=new MockHttpServletResponse();
            new RequestDiagnostics().doFilter(request,response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(503));
            String id=response.getHeader("X-Request-ID");
            assertThat(id).matches("[0-9a-f-]{36}");
            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getFormattedMessage()).contains(id,"/api/users/{userId}/settings","status=503","durationMs=")
                    .doesNotContain("private-person","private-token","private-authorization","untrusted-id");
        } finally { logger.detachAppender(events); events.stop(); }
    }
    @Test void successfulRequestsHaveIndependentIdsAndDoNotCreateSessions() throws Exception {
        var filter=new RequestDiagnostics();
        var first=new MockHttpServletResponse(); var second=new MockHttpServletResponse();
        var request=new MockHttpServletRequest("GET","/api/health");
        filter.doFilter(request,first,(req,res)->{});
        filter.doFilter(new MockHttpServletRequest("GET","/api/health"),second,(req,res)->{});
        assertThat(first.getHeader("X-Request-ID")).isNotEqualTo(second.getHeader("X-Request-ID"));
        assertThat(request.getSession(false)).isNull();
    }
}
