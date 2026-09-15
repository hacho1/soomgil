package backend;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiUsageLimitsTests {
    @Test void aliasesShareBudgetsWhileTypingReadingAndDeletingStayIndependent() {
        for (String path:new String[]{"/api/places","/api/places/search","/api/places/nearby","/api/places/discover","/api/places/home","/api/places/recommendations"})
            assertThat(ApiUsageLimits.rule("GET",path)).isEqualTo(new ApiUsageLimits.Rule("search",120,1));
        assertThat(ApiUsageLimits.rule("GET","/api/suggestions")).isEqualTo(ApiUsageLimits.rule("GET","/api/suggestions/related"));
        assertThat(ApiUsageLimits.rule("PUT","/api/users/{userId}/diaries/{id}").scope()).isEqualTo("diary-write");
        assertThat(ApiUsageLimits.rule("GET","/api/users/{userId}/diaries").scope()).isEqualTo("records-read");
        assertThat(ApiUsageLimits.rule("DELETE","/api/users/{userId}/diaries/{id}").scope()).isEqualTo("records-delete");
        for (String path:new String[]{"/api/auth/login","/api/health","/api/admin/notices"}) {
            assertThat(ApiUsageLimits.rule("GET",path)).isNull();
            assertThat(ApiUsageLimits.rule("POST",path)).isNull();
        }
    }

    @Test void usesAuthenticatedIdentityAndTrustedRemoteAddressNotCallerSuppliedHeaders() {
        var limits=mock(AuthRateLimit.class); var auth=mock(SessionAuth.class);
        when(auth.currentUser()).thenReturn("owner");
        var interceptor=new ApiUsageLimits(limits,auth);
        var request=new MockHttpServletRequest("GET","/api/places/search");
        request.setRemoteAddr("192.0.2.1"); request.addHeader("X-Forwarded-For","198.51.100.1");
        request.addParameter("userId","someone-else");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/places/search");
        assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();
        verify(limits).take("api-ip-search","192.0.2.1",600,Duration.ofMinutes(1));
        verify(limits).take("api-search","user:owner",120,Duration.ofMinutes(1));
        when(auth.currentUser()).thenReturn("");
        interceptor.preHandle(request,new MockHttpServletResponse(),new Object());
        verify(limits).take("api-search","guest:192.0.2.1",120,Duration.ofMinutes(1));
    }
}
