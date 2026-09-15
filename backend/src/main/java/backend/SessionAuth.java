package backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionAuth {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final HttpSessionSecurityContextRepository repository;
    private final String adminUsername;
    private final boolean sharing;

    public SessionAuth(HttpServletRequest request, HttpServletResponse response,
            HttpSessionSecurityContextRepository repository,
            @Value("${soomgil.admin.username:}") String adminUsername,
            @Value("${soomgil.sharing.enabled:false}") boolean sharing) {
        this.request = request;
        this.response = response;
        this.repository = repository;
        this.adminUsername = adminUsername;
        this.sharing = sharing;
    }

    public String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String user=authentication == null || authentication instanceof AnonymousAuthenticationToken ? "" : authentication.getName();
        return blockedForSharing(user) ? "" : user;
    }

    public String requireUser() {
        String user = currentUser();
        if (user.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return user;
    }

    public void requireOwner(String user) {
        if (!requireUser().equals(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 정보만 조회하거나 수정할 수 있습니다.");
    }

    public boolean isAdmin() {
        return !sharing && !adminUsername.isBlank() && adminUsername.equals(currentUser());
    }

    public boolean isReservedUsername(String username) {
        return blockedForSharing(username) || (!adminUsername.isBlank() && adminUsername.equalsIgnoreCase(username));
    }

    private boolean blockedForSharing(String username) {
        return sharing && java.util.Set.of("test1","test01","demo").contains(username.toLowerCase(java.util.Locale.ROOT));
    }

    public void login(String username, boolean remember) {
        if (blockedForSharing(username)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"공유 테스트에서는 새로 가입한 계정으로 로그인해주세요.");
        var session = request.getSession(true);
        request.changeSessionId();
        session.setMaxInactiveInterval(remember ? 30 * 24 * 60 * 60 : 12 * 60 * 60);
        if (remember) request.setAttribute("soomgil.remember", true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username, null,
                AuthorityUtils.createAuthorityList(!adminUsername.isBlank() && username.equals(adminUsername) ? "ROLE_ADMIN" : "ROLE_USER")));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }

    public void logout() {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
