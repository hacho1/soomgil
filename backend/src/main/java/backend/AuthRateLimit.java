package backend;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthRateLimit {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate independent;
    public AuthRateLimit(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @PostConstruct void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth_rate_limits (bucket_hash VARCHAR(64) PRIMARY KEY, hits INT NOT NULL, reset_at BIGINT NOT NULL)");
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String client(HttpServletRequest request) { return request.getRemoteAddr(); }
    public void take(String scope, String key, int maximum, Duration window) {
        String bucket = digest(scope + "\n" + key); long now = clock.millis();
        Long wait = independent.execute(status -> {
            try { jdbc.update("INSERT INTO auth_rate_limits(bucket_hash,hits,reset_at) VALUES(?,0,?)", bucket, now + window.toMillis()); }
            catch (DuplicateKeyException ignored) { }
            // Reserve attempts atomically, including parallel requests and failed outer transactions.
            int changed = jdbc.update("UPDATE auth_rate_limits SET hits=CASE WHEN reset_at<=? THEN 1 ELSE hits+1 END, reset_at=CASE WHEN reset_at<=? THEN ? ELSE reset_at END WHERE bucket_hash=? AND (reset_at<=? OR hits<?)", now, now, now + window.toMillis(), bucket, now, maximum);
            if (changed == 1) return 0L;
            return Math.max(1L, (jdbc.queryForObject("SELECT reset_at FROM auth_rate_limits WHERE bucket_hash=?", Long.class, bucket) - now + 999) / 1000);
        });
        if (wait != null && wait > 0) throw new Exceeded(wait);
    }
    public void clear(String scope, String key) {
        independent.executeWithoutResult(status -> jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_hash=?", digest(scope + "\n" + key)));
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    void cleanup() { jdbc.update("DELETE FROM auth_rate_limits WHERE reset_at<?", clock.millis() - Duration.ofDays(1).toMillis()); }

    public static class Exceeded extends RuntimeException {
        public final long seconds;
        public Exceeded(long seconds) { super("요청이 많아요. 약 " + (seconds>=60 ? (seconds+59)/60+"분" : Math.max(1,seconds)+"초") + " 후 다시 시도해주세요."); this.seconds = seconds; }
    }
}
