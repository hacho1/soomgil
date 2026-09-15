package backend;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerification {
    private static final Set<String> PURPOSES = Set.of("signup", "find-id", "reset-password", "change-email", "verify-email");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final EmailDelivery mail;
    private final AuthRateLimit limits;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public EmailVerification(JdbcTemplate jdbc, Clock clock, EmailDelivery mail, AuthRateLimit limits, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock; this.mail = mail; this.limits = limits;
        this.transaction = new TransactionTemplate(manager);
    }
    @PostConstruct void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS email_challenges (
              id VARCHAR(64) PRIMARY KEY, email VARCHAR(254) NOT NULL, purpose VARCHAR(24) NOT NULL,
              subject_id VARCHAR(255) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
              code_hash VARCHAR(100) NOT NULL, eligible BOOLEAN NOT NULL, expires_at BIGINT NOT NULL,
              attempts INT NOT NULL DEFAULT 0, confirmed BOOLEAN NOT NULL DEFAULT FALSE,
              proof_hash VARCHAR(64), proof_expires BIGINT NOT NULL DEFAULT 0, consumed BOOLEAN NOT NULL DEFAULT FALSE
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_email_challenge_email ON email_challenges(email,purpose)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_email_challenge_proof ON email_challenges(proof_hash)");
    }
    public boolean available() { return mail.available(); }
    public static String email(Object supplied) {
        String email = supplied == null ? "" : supplied.toString().trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw invalid("이메일 형식을 확인해주세요.");
        return email;
    }
    public Map<String, Object> request(String email, String purpose, String subject, String client) {
        if (!PURPOSES.contains(purpose)) throw invalid("인증 목적을 확인해주세요.");
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이메일 인증을 준비 중이에요. 잠시 후 다시 이용해주세요.");
        limits.take("mail-ip", client, 10, Duration.ofMinutes(15));
        limits.take("mail-resend", email, 1, Duration.ofSeconds(60));
        limits.take("mail-recipient", email, 5, Duration.ofHours(1));
        limits.take("mail-global-hour", "all", 100, Duration.ofHours(1));
        limits.take("mail-global-day", "all", 1000, Duration.ofDays(1));
        Map<String, Object> account = null;
        if (purpose.equals("change-email") || purpose.equals("verify-email")) {
            if (subject.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
            account = accountByUser(subject);
            if (purpose.equals("verify-email") && !email.equals(account.get("email"))) throw invalid("내 계정의 이메일을 확인해주세요.");
        } else if (!purpose.equals("signup")) {
            account = jdbc.queryForList("SELECT * FROM user_accounts WHERE email=?", email).stream().findFirst().orElse(null);
            subject = account == null ? "" : account.get("username").toString();
        }
        String fingerprint = account == null ? "" : fingerprint(account);
        boolean eligible = purpose.equals("signup") || (account != null && (purpose.endsWith("email") || Boolean.TRUE.equals(account.get("email_verified"))));
        String id = token(), code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        String codeHash = encoder.encode(code), owner = subject;
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE email_challenges SET consumed=TRUE WHERE email=? AND purpose=? AND subject_id=?", email, purpose, owner);
            jdbc.update("INSERT INTO email_challenges(id,email,purpose,subject_id,fingerprint,code_hash,eligible,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, email, purpose, owner, fingerprint, codeHash, eligible, clock.millis() + Duration.ofMinutes(10).toMillis());
        });
        try {
            // Unknown recovery accounts receive the same response without sending mail.
            if (eligible) mail.verification(email, code, purpose, () -> jdbc.update("UPDATE email_challenges SET consumed=TRUE WHERE id=?", id));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            jdbc.update("UPDATE email_challenges SET consumed=TRUE WHERE id=?", id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "메일 요청이 많아요. 잠시 후 다시 시도해주세요.");
        }
        return Map.of("challengeId", id, "expiresIn", 600, "resendAfter", 60,
                "message", purpose.equals("signup") || purpose.endsWith("email") ? "인증 메일을 요청했어요. 스팸함도 확인해주세요." : "가입된 이메일이라면 인증번호가 발송됩니다. 스팸함도 확인해주세요.");
    }
    public Map<String, Object> confirm(String id, String code, String client) {
        limits.take("mail-confirm-ip", client, 50, Duration.ofMinutes(15));
        if (!id.matches("[A-Za-z0-9_-]{43}") || !code.matches("[0-9]{6}")) throw invalid("6자리 인증번호를 확인해주세요.");
        Map<String, Object> result = transaction.execute(status -> {
            var rows = jdbc.queryForList("SELECT * FROM email_challenges WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            if (Boolean.TRUE.equals(row.get("consumed")) || Boolean.TRUE.equals(row.get("confirmed")) || number(row, "expires_at") <= clock.millis() || number(row, "attempts") >= 5) return null;
            jdbc.update("UPDATE email_challenges SET attempts=attempts+1 WHERE id=?", id);
            if (!encoder.matches(code, row.get("code_hash").toString()) || !Boolean.TRUE.equals(row.get("eligible"))) return null;
            String proof = token();
            jdbc.update("UPDATE email_challenges SET confirmed=TRUE,proof_hash=?,proof_expires=? WHERE id=?", AuthRateLimit.digest(proof), clock.millis() + Duration.ofMinutes(10).toMillis(), id);
            String provider = "local";
            if (!row.get("subject_id").toString().isBlank()) provider = accountByUser(row.get("subject_id").toString()).get("auth_provider").toString();
            return Map.<String, Object>of("proof", proof, "expiresIn", 600, "provider", provider);
        });
        if (result == null) throw invalid("인증번호가 올바르지 않거나 만료됐어요. 5회 틀리면 새 번호를 요청해주세요.");
        return result;
    }
    // Must participate in the caller's account transaction: rollback also restores the proof.
    public Map<String, Object> consume(Object supplied, String email, String purpose, String subject) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Verification requires an account transaction");
        String proof = supplied == null ? "" : supplied.toString();
        if (!proof.matches("[A-Za-z0-9_-]{43}")) throw invalid("이메일 인증을 먼저 완료해주세요.");
        var rows = jdbc.queryForList("SELECT * FROM email_challenges WHERE proof_hash=? FOR UPDATE", AuthRateLimit.digest(proof));
        if (rows.isEmpty()) throw invalid("이메일 인증을 다시 진행해주세요.");
        var row = rows.get(0);
        if (!email.equals(row.get("email")) || !purpose.equals(row.get("purpose")) || (subject != null && !subject.equals(row.get("subject_id")))
                || Boolean.TRUE.equals(row.get("consumed")) || number(row, "proof_expires") <= clock.millis()) throw invalid("이메일 인증이 만료됐거나 사용할 수 없어요. 다시 인증해주세요.");
        jdbc.update("UPDATE email_challenges SET consumed=TRUE WHERE id=?", row.get("id"));
        return row;
    }
    public Map<String, Object> verifiedAccount(Map<String, Object> proof) {
        var accounts = jdbc.queryForList("SELECT * FROM user_accounts WHERE username=? FOR UPDATE", proof.get("subject_id"));
        if (accounts.isEmpty()) throw invalid("계정 정보를 확인할 수 없어요.");
        var account = accounts.get(0);
        if (!fingerprint(account).equals(proof.get("fingerprint"))) throw invalid("계정 정보가 변경됐어요. 이메일 인증을 다시 진행해주세요.");
        return account;
    }
    private Map<String, Object> accountByUser(String username) {
        var accounts = jdbc.queryForList("SELECT * FROM user_accounts WHERE username=?", username);
        if (accounts.isEmpty()) throw invalid("계정 정보를 확인할 수 없어요.");
        return accounts.get(0);
    }
    static String fingerprint(Map<String, Object> account) {
        return AuthRateLimit.digest(account.get("username") + "\n" + account.get("email") + "\n" + account.get("auth_provider") + "\n" + account.get("password_hash"));
    }
    public void passwordChanged(String user, String email) {
        jdbc.update("UPDATE email_challenges SET consumed=TRUE WHERE subject_id=?", user);
        limits.clear("login-account", user);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { mail.passwordChanged(email); }
                catch (RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(EmailVerification.class).warn("Password-change notification could not be queued"); }
            }
        });
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    void cleanup() { jdbc.update("DELETE FROM email_challenges WHERE expires_at<? AND proof_expires<?", clock.millis() - Duration.ofDays(1).toMillis(), clock.millis() - Duration.ofDays(1).toMillis()); }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
