package backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AuthRateLimitTests {
    @Test void attemptsSurviveRollbackAndNewServiceInstanceButExpireOnTime() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:rate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource); var manager = new DataSourceTransactionManager(dataSource);
        var start = Instant.parse("2026-09-14T00:00:00Z");
        var limits = new AuthRateLimit(jdbc, Clock.fixed(start, ZoneOffset.UTC), manager); limits.initialize();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            limits.take("test", "one", 1, Duration.ofMinutes(15)); throw new IllegalStateException("failed login");
        })).isInstanceOf(IllegalStateException.class);
        var restarted = new AuthRateLimit(jdbc, Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC), manager);
        assertThatThrownBy(() -> restarted.take("test", "one", 1, Duration.ofMinutes(15))).isInstanceOf(AuthRateLimit.Exceeded.class);
        var expired = new AuthRateLimit(jdbc, Clock.fixed(start.plusSeconds(900), ZoneOffset.UTC), manager);
        assertThatCode(() -> expired.take("test", "one", 1, Duration.ofMinutes(15))).doesNotThrowAnyException();
    }
    @Test void parallelRequestsCannotExceedBudget() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:parallel-rate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var limits = new AuthRateLimit(jdbc, Clock.systemUTC(), new DataSourceTransactionManager(dataSource)); limits.initialize();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var work = new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 16; i++) work.add(() -> {
                try { limits.take("parallel", "same", 5, Duration.ofMinutes(15)); return 1; }
                catch (AuthRateLimit.Exceeded failure) { return 0; }
            });
            int allowed = 0; for (var result : pool.invokeAll(work)) allowed += result.get();
            assertThat(allowed).isEqualTo(5);
        } finally { pool.shutdownNow(); }
    }
}
