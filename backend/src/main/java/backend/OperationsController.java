package backend;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/operations")
public class OperationsController {
    private final BackupService backups;
    private final JdbcTemplate jdbc;
    private final AuthRateLimit limits;
    private final SessionAuth auth;
    public OperationsController(BackupService backups, JdbcTemplate jdbc, AuthRateLimit limits, SessionAuth auth) {
        this.backups = backups; this.jdbc = jdbc; this.limits = limits; this.auth = auth;
    }
    @GetMapping public Map<String, Object> status() {
        boolean database;
        try { database = jdbc.queryForObject("SELECT 1", Integer.class) == 1; } catch (Exception ignored) { database = false; }
        return Map.of("database", database ? "up" : "down", "uptimeSeconds", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000, "backup", backups.status());
    }
    @PostMapping("/backup") public ResponseEntity<?> backup() {
        limits.take("manual-backup", auth.requireUser(), 1, java.time.Duration.ofMinutes(1));
        if (!backups.supported()) return ResponseEntity.status(409).body(Map.of("message", "현재 DB 형식은 이 백업 기능을 지원하지 않습니다. H2 파일 DB용 기능입니다."));
        if (!backups.start()) return ResponseEntity.status(409).body(Map.of("message", "백업을 이미 진행하고 있습니다."));
        return ResponseEntity.accepted().body(Map.of("accepted", true));
    }
}
