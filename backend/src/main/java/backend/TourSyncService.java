package backend;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TourSyncService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TourSyncService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final TourCatalog catalog;
    private final PlaceQueries places;
    private final boolean enabled;
    private volatile boolean ready;

    public TourSyncService(JdbcTemplate jdbc, TourCatalog catalog, PlaceQueries places,
                           @Value("${soomgil.tour-sync-enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.catalog = catalog; this.places = places; this.enabled = enabled;
    }
    @PostConstruct
    void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tour_sync_state (
                id INT PRIMARY KEY, mode VARCHAR(20) NOT NULL, state VARCHAR(30) NOT NULL,
                next_page INT NOT NULL, page_size INT NOT NULL, run_total BIGINT NOT NULL,
                processed BIGINT NOT NULL, skipped BIGINT NOT NULL, run_started_at BIGINT NOT NULL,
                full_completed_at BIGINT NOT NULL, last_completed_at BIGINT NOT NULL,
                modified_since VARCHAR(8) NOT NULL, next_run_at BIGINT NOT NULL,
                error_count INT NOT NULL, paused BOOLEAN NOT NULL,
                lease_owner VARCHAR(40), lease_until BIGINT NOT NULL
            )
            """);
        jdbc.execute("ALTER TABLE tour_sync_state ADD COLUMN IF NOT EXISTS modified_until VARCHAR(8) DEFAULT ''");
        jdbc.execute("ALTER TABLE tour_sync_state ADD COLUMN IF NOT EXISTS last_started_at BIGINT DEFAULT 0");
        jdbc.execute("ALTER TABLE tour_sync_state ADD COLUMN IF NOT EXISTS catalog_version INT DEFAULT 0");
        jdbc.execute("ALTER TABLE tour_sync_state ADD COLUMN IF NOT EXISTS run_catalog_version INT DEFAULT 0");
        try { jdbc.update("INSERT INTO tour_sync_state(id,mode,state,next_page,page_size,run_total,processed,skipped,run_started_at,full_completed_at,last_completed_at,modified_since,next_run_at,error_count,paused,lease_owner,lease_until) VALUES(1,'full','pending',1,500,0,0,0,0,0,0,'',0,0,FALSE,NULL,0)"); }
        catch (org.springframework.dao.DuplicateKeyException ignored) { }
        // Preserve active checkpoints and pauses; an idle legacy catalog catches up before a full metadata pass.
        jdbc.update("UPDATE tour_sync_state SET next_run_at=0 WHERE id=1 AND mode='idle' AND catalog_version<?", PlaceContent.CATALOG_VERSION);
    }
    @EventListener(ApplicationReadyEvent.class)
    void ready() { ready = true; }

    @Scheduled(initialDelayString = "${soomgil.tour-sync-initial-delay-ms:10000}", fixedDelayString = "${soomgil.tour-sync-tick-ms:2000}")
    public void tick() { if (ready && enabled) runOnePage(); }

    void runOnePage() {
        if (!catalog.configured()) return;
        long now = System.currentTimeMillis();
        String owner = UUID.randomUUID().toString();
        if (jdbc.update("UPDATE tour_sync_state SET lease_owner=?,lease_until=? WHERE id=1 AND paused=FALSE AND next_run_at<=? AND lease_until<?", owner, now + 120000, now, now) != 1) return;
        try {
            var row = row();
            if ("idle".equals(row.get("mode"))) {
                // Catch up dated tombstones before a weekly full pass, including after long downtime.
                boolean caughtUpToday = value(row, "last_completed_at") >= Instant.ofEpochMilli(now).atZone(KOREA).toLocalDate().atStartOfDay(KOREA).toInstant().toEpochMilli();
                boolean full = value(row, "full_completed_at") == 0 || weeklyDue(row, now) && caughtUpToday;
                long watermark = value(row, "last_started_at") > 0 ? value(row, "last_started_at") : value(row, "last_completed_at");
                String since = full ? "" : Instant.ofEpochMilli(watermark).atZone(KOREA).toLocalDate().minusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE);
                String until = full ? "" : Instant.ofEpochMilli(now).atZone(KOREA).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
                jdbc.update("UPDATE tour_sync_state SET mode=?,state='running',next_page=1,run_total=0,processed=0,skipped=0,run_started_at=?,modified_since=?,modified_until=?,error_count=0 WHERE id=1 AND lease_owner=?", full ? "full" : "delta", now, since, until, owner);
                row = row();
            } else if (value(row, "run_started_at") == 0) {
                jdbc.update("UPDATE tour_sync_state SET run_started_at=?,state='running' WHERE id=1 AND lease_owner=?", now, owner);
            }
            final boolean full = "full".equals(row.get("mode"));
            if (full && value(row,"next_page") == 1)
                jdbc.update("UPDATE tour_sync_state SET run_catalog_version=? WHERE id=1 AND lease_owner=?", PlaceContent.CATALOG_VERSION,owner);
            final String syncDate = String.valueOf(row.get("modified_since"));
            final String through = String.valueOf(row.get("modified_until"));
            final long alreadyProcessed = value(row, "processed");
            String result = catalog.syncPage((int) value(row, "next_page"), (int) value(row, "page_size"), String.valueOf(row.get("modified_since")), batch -> {
                long finished = System.currentTimeMillis();
                long total = full ? batch.total() : alreadyProcessed + Math.max(0, batch.total() - (long) (batch.page() - 1) * batch.size());
                int changed;
                if (batch.hasMore()) {
                    changed = jdbc.update("UPDATE tour_sync_state SET state='running',next_page=?,run_total=?,processed=processed+?,skipped=skipped+?,next_run_at=?,error_count=0 WHERE id=1 AND lease_owner=? AND lease_until>?",
                            batch.page() + 1, total, batch.received(), batch.skipped(), finished + 2000, owner, finished);
                } else if (!full && syncDate.compareTo(through) < 0) {
                    // TourAPI filters an exact modification date, not an inclusive date range.
                    String nextDate = java.time.LocalDate.parse(syncDate, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
                    changed = jdbc.update("UPDATE tour_sync_state SET state='running',next_page=1,modified_since=?,run_total=?,processed=processed+?,skipped=skipped+?,next_run_at=?,error_count=0 WHERE id=1 AND lease_owner=? AND lease_until>?",
                            nextDate, total, batch.received(), batch.skipped(), finished + 2000, owner, finished);
                } else {
                    changed = jdbc.update("UPDATE tour_sync_state SET mode='idle',state='waiting',run_total=?,processed=processed+?,skipped=skipped+?,last_completed_at=?,last_started_at=run_started_at,full_completed_at=CASE WHEN ? THEN ? ELSE full_completed_at END,catalog_version=CASE WHEN ? THEN run_catalog_version ELSE catalog_version END,next_run_at=?,error_count=0 WHERE id=1 AND lease_owner=? AND lease_until>?",
                            total, batch.received(), batch.skipped(), finished, full, finished, full, (!full && weeklyDue(row(), finished) || full && value(row(),"run_catalog_version")<PlaceContent.CATALOG_VERSION) ? finished + 2000 : nextDaily(finished), owner, finished);
                }
                if (changed != 1) throw new IllegalStateException("Sync lease expired");
            });
            if (!"connected".equals(result)) defer(owner, result);
        } catch (Exception failure) {
            // Persist a bounded state, never the upstream exception or URL containing a key.
            log.warn("Tour sync page deferred ({})", failure.getClass().getSimpleName());
            defer(owner, "retrying");
        } finally { jdbc.update("UPDATE tour_sync_state SET lease_owner=NULL,lease_until=0 WHERE id=1 AND lease_owner=?", owner); }
    }
    private void defer(String owner, String state) {
        long now = System.currentTimeMillis();
        long errors = value(row(), "error_count");
        long next = "budget-limited".equals(state) ? Instant.ofEpochMilli(now).atZone(KOREA).toLocalDate().plusDays(1).atStartOfDay(KOREA).plusMinutes(5).toInstant().toEpochMilli()
                : now + ("busy".equals(state) ? 5000 : Math.min(3600000, 60000L * (1L << Math.min(6, errors))));
        jdbc.update("UPDATE tour_sync_state SET state=?,next_run_at=?,error_count=error_count+1 WHERE id=1 AND lease_owner=?", state, next, owner);
    }
    static long nextDaily(long now) {
        var local = Instant.ofEpochMilli(now).atZone(KOREA);
        var next = local.toLocalDate().atTime(3, 0).atZone(KOREA);
        if (!next.isAfter(local)) next = next.plusDays(1);
        return next.toInstant().toEpochMilli();
    }
    private Map<String, Object> row() { return jdbc.queryForMap("SELECT * FROM tour_sync_state WHERE id=1"); }
    private static long value(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static boolean weeklyDue(Map<String, Object> row, long now) { return value(row,"catalog_version") < PlaceContent.CATALOG_VERSION || value(row, "full_completed_at") + 7L * 86400000 <= now; }

    public Map<String, Object> status() {
        var row = row();
        var status = new LinkedHashMap<String, Object>();
        status.put("enabled", enabled); status.put("configured", catalog.configured());
        status.put("state", !enabled ? "disabled" : !catalog.configured() ? "not-configured" : Boolean.TRUE.equals(row.get("paused")) ? "paused" : row.get("state"));
        status.put("mode", row.get("mode")); status.put("page", row.get("next_page")); status.put("pageSize", row.get("page_size"));
        status.put("syncDate", row.get("modified_since")); status.put("throughDate", row.get("modified_until"));
        status.put("total", row.get("run_total")); status.put("processed", row.get("processed")); status.put("skipped", row.get("skipped"));
        status.put("initialComplete", value(row, "full_completed_at") > 0); status.put("storedTotal", places.publicCount());
        status.put("contentTypesRefreshed", value(row,"catalog_version") >= PlaceContent.CATALOG_VERSION);
        status.put("lastFullAt", row.get("full_completed_at")); status.put("lastCompletedAt", row.get("last_completed_at"));
        status.put("nextRunAt", row.get("next_run_at")); status.put("paused", row.get("paused"));
        status.put("dailyCalls", catalog.dailyCalls()); status.put("dailyLimit", catalog.dailyLimit());
        return status;
    }
    public void control(String action) {
        switch (action) {
            case "pause" -> jdbc.update("UPDATE tour_sync_state SET paused=TRUE WHERE id=1");
            case "resume", "run" -> jdbc.update("UPDATE tour_sync_state SET paused=FALSE,next_run_at=0 WHERE id=1");
            default -> throw new IllegalArgumentException("Unknown sync action");
        }
    }
}
