package backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/naver")
public class NaverAuthController {
    private static final String STATE = "naver.state", EXPIRES = "naver.expires", REMEMBER = "naver.remember";
    private static final String PROFILE = "naver.profile", RESULT = "naver.result";
    private static final long LIFETIME = 10 * 60 * 1000L;
    private final NaverIdentity identity;
    private final SoomgilApiController accounts;
    private final SessionAuth auth;
    private final SecureRandom random = new SecureRandom();

    public NaverAuthController(NaverIdentity identity, SoomgilApiController accounts, SessionAuth auth) {
        this.identity = identity; this.accounts = accounts; this.auth = auth;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!identity.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "네이버 로그인 연결을 준비하고 있어요.");
        String origin = request.getHeader("Origin");
        if (origin != null && !identity.origin().equals(origin)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "네이버 로그인은 " + identity.origin() + " 주소에서 이용해주세요.");
        if (!(body.getOrDefault("autoLogin", false) instanceof Boolean)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 로그인 설정을 확인해주세요.");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var session = request.getSession(true);
        clear(session);
        session.setAttribute(STATE, state);
        session.setAttribute(EXPIRES, System.currentTimeMillis() + LIFETIME);
        session.setAttribute(REMEMBER, Boolean.TRUE.equals(body.get("autoLogin")));
        return noStore(Map.of("url", identity.authorizeUrl(state)));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(defaultValue = "") String code,
                                         @RequestParam(defaultValue = "") String state,
                                         @RequestParam(defaultValue = "") String error, HttpServletRequest request) {
        if (!identity.available()) return redirect("unavailable");
        var session = request.getSession(false);
        if (session == null) return redirect("expired");
        Object expected = session.getAttribute(STATE);
        session.removeAttribute(STATE);
        if (!(expected instanceof String token) || state.length() > 128 || !fresh(session)
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            clear(session); return redirect("expired");
        }
        if (!error.isBlank()) { clear(session); return redirect("cancelled"); }
        if (code.isBlank() || code.length() > 2048) { clear(session); return redirect("failed"); }
        try {
            var profile = identity.exchange(code, state);
            var existing = accounts.findNaverUsername(profile.id());
            if (existing.isPresent()) {
                auth.login(existing.get(), Boolean.TRUE.equals(session.getAttribute(REMEMBER)));
                clear(session); session.setAttribute(RESULT, existing.get());
            } else {
                if (accounts.naverEmailConflict(profile.email())) { clear(session); return redirect("existing_account"); }
                session.setAttribute(PROFILE, Map.of("id", profile.id(), "email", profile.email(), "nickname", profile.nickname(), "image", profile.image()));
                session.setAttribute(EXPIRES, System.currentTimeMillis() + LIFETIME);
            }
            return redirect("");
        } catch (RuntimeException failure) {
            clear(session); return redirect("failed");
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && auth.currentUser().equals(session.getAttribute(RESULT))) return noStore(Map.of("authenticated", true));
        var profile = profile(session);
        return noStore(Map.of("authenticated", false, "email", profile.get("email"), "nickname", accounts.naverNickname(profile.get("nickname")), "image", profile.get("image")));
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        var session = request.getSession(false);
        var profile = profile(session);
        String user = accounts.registerNaver(new NaverIdentity.Profile(profile.get("id"), profile.get("email"), profile.get("nickname"), profile.get("image")), body);
        auth.login(user, Boolean.TRUE.equals(session.getAttribute(REMEMBER)));
        clear(session); session.setAttribute(RESULT, user);
        return noStore(Map.of("ok", true));
    }

    @DeleteMapping("/pending")
    public ResponseEntity<?> cancel(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) clear(session);
        return noStore(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> profile(HttpSession session) {
        if (session == null || !fresh(session) || !(session.getAttribute(PROFILE) instanceof Map)) {
            if (session != null) clear(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "네이버 인증 시간이 만료됐어요. 다시 로그인해주세요.");
        }
        return (Map<String, String>) session.getAttribute(PROFILE);
    }

    private boolean fresh(HttpSession session) {
        return session.getAttribute(EXPIRES) instanceof Long expires && expires > System.currentTimeMillis();
    }

    private void clear(HttpSession session) {
        for (String key : new String[]{STATE, EXPIRES, REMEMBER, PROFILE, RESULT}) session.removeAttribute(key);
    }

    private ResponseEntity<Void> redirect(String error) {
        String origin = identity.available() ? identity.origin() : "";
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .location(URI.create(origin + "/auth/naver/complete" + (error.isEmpty() ? "" : "?error=" + error))).build();
    }

    private ResponseEntity<?> noStore(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
