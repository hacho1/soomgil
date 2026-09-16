package backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAuthTests {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    SessionAuth auth(String profile, String administrator, boolean sharing) {
        var env = new MockEnvironment(); env.setActiveProfiles(profile);
        return new SessionAuth(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HttpSessionSecurityContextRepository(), administrator, sharing, env);
    }

    void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, null, AuthorityUtils.createAuthorityList(role)));
    }

    @Test void productionBlocksKnownDemoLoginsRegistrationsAndRestoredSessions() {
        var auth = auth("production", "operator", false);
        for (String username : new String[]{"test1", "test01", "DEMO"}) {
            assertThat(auth.isReservedUsername(username)).isTrue();
            assertThatThrownBy(() -> auth.login(username, true)).isInstanceOf(ResponseStatusException.class);
            authenticate(username, "ROLE_ADMIN");
            assertThat(auth.currentUser()).isEmpty();
            assertThat(auth.isAdmin()).isFalse();
            assertThatThrownBy(auth::requireUser).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void localDevelopmentStillAllowsDemoAccounts() {
        var auth = auth("default", "operator", false);
        assertThat(auth.isReservedUsername("test1")).isFalse();
        authenticate("test1", "ROLE_USER");
        assertThat(auth.requireUser()).isEqualTo("test1");
    }

    @Test void oldAdministratorRoleDoesNotGrantOperationalAccess() {
        var auth = auth("production", "newoperator", false);
        authenticate("previousoperator", "ROLE_ADMIN");
        var backups = mock(BackupService.class);
        var controller = new OperationsController(backups, mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(AuthRateLimit.class), auth);
        assertThatThrownBy(controller::status).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(controller::backup).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(backups);
        authenticate("newoperator", "ROLE_ADMIN");
        assertThatCode(auth::requireAdmin).doesNotThrowAnyException();
    }

    @Test void sharingNeverAllowsAdministratorAccess() {
        var auth = auth("sharing", "operator", true);
        authenticate("operator", "ROLE_ADMIN");
        assertThatThrownBy(auth::requireAdmin).isInstanceOf(ResponseStatusException.class);
    }
}
