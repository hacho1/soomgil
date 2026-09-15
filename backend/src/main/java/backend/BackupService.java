package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BackupService {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path directory;
    private final String replica, username, password;
    private final boolean enabled;
    private final int keep;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "db-backup"); t.setDaemon(true); return t; });
    private volatile String state = "idle", replicaState = "not-configured";
    private volatile long lastAttempt;
    private volatile Map<String, Object> last = Map.of();
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String FILE_PATTERN = "soomgil-db-[0-9]{8}T[0-9]{9}Z-[a-f0-9]{8}\\.zip";

    public BackupService(DataSource dataSource, ObjectMapper mapper, Clock clock,
            @Value("${soomgil.backup.enabled:true}") boolean enabled,
            @Value("${soomgil.backup.directory:../backup/database}") String directory,
            @Value("${soomgil.backup.replica:}") String replica,
            @Value("${soomgil.backup.keep:14}") int keep,
            @Value("${spring.datasource.username:sa}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.dataSource = dataSource; this.mapper = mapper; this.clock = clock; this.enabled = enabled;
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.replica = replica;
        this.keep = Math.max(2, Math.min(90, keep)); this.username = username; this.password = password;
        loadLast();
    }
    private void loadLast() {
        try {
            if (!Files.isDirectory(directory)) return;
            try (var files = Files.list(directory)) {
                for (var path : files.filter(p -> p.getFileName().toString().matches(FILE_PATTERN + "\\.json")).sorted(Comparator.reverseOrder()).toList()) {
                    var record = ownedRecord(path);
                    if (record != null) {
                        last = mapper.convertValue(record, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        replicaState = record.path("replicaState").asText("not-configured"); break;
                    }
                }
            }
        } catch (Exception ignored) { state = "metadata-error"; }
    }
    public boolean supported() {
        try (var c = dataSource.getConnection()) { return c.getMetaData().getURL().startsWith("jdbc:h2:file:"); }
        catch (Exception ignored) { return false; }
    }
    public Map<String, Object> status() {
        boolean supported = supported();
        long completed = ((Number) last.getOrDefault("completedAt", 0L)).longValue();
        return Map.of("enabled", enabled, "supported", supported, "state", running.get() ? "running" : state,
                "last", last, "lastAttempt", lastAttempt, "stale", completed == 0 || clock.millis() - completed > Duration.ofHours(26).toMillis(),
                "replicaConfigured", !replica.isBlank(), "replicaState", replicaState, "keep", keep, "nextRunAt", nextRun().toEpochMilli());
    }
    private Instant nextRun() {
        var now = clock.instant().atZone(ZONE); var next = now.toLocalDate().atTime(4, 0).atZone(ZONE);
        return (next.isAfter(now) ? next : next.plusDays(1)).toInstant();
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    void catchUp() {
        long completed = ((Number) last.getOrDefault("completedAt", 0L)).longValue();
        Instant due = nextRun().atZone(ZONE).minusDays(1).toInstant();
        if (enabled && supported() && completed < due.toEpochMilli() && clock.millis() - lastAttempt >= Duration.ofHours(1).toMillis()) start();
    }
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    void scheduled() { if (enabled && supported()) start(); }
    public boolean start() {
        if (!supported() || !running.compareAndSet(false, true)) return false;
        lastAttempt = clock.millis();
        worker.execute(() -> { try { createBackup(); } finally { running.set(false); } });
        return true;
    }
    void createBackup() {
        Path partial = null;
        try {
            Files.createDirectories(directory);
            Path root = directory.toRealPath();
            if (Files.getFileStore(root).getUsableSpace() < 536870912L) throw new IllegalStateException("Insufficient backup space");
            try (var channel = FileChannel.open(root.resolve(".backup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) { state = "busy"; return; }
                String name = "soomgil-db-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(clock.instant()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
                partial = root.resolve(name + ".partial"); Path archive = root.resolve(name);
                state = "running";
                try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement("BACKUP TO ?")) {
                    if (!connection.getMetaData().getURL().startsWith("jdbc:h2:file:")) throw new IllegalStateException("Unsupported database");
                    statement.setString(1, partial.toString()); statement.setQueryTimeout(300); statement.execute();
                }
                var counts = BackupArchive.verify(partial, root.resolve("restore-work"), username, password);
                String hash = BackupArchive.sha256(partial);
                Files.move(partial, archive); partial = null;
                var record = new LinkedHashMap<String, Object>();
                record.put("format", 1); record.put("file", name); record.put("sha256", hash); record.put("bytes", Files.size(archive));
                record.put("verified", true); record.put("completedAt", clock.millis()); record.put("tables", counts);
                replicaState = replica.isBlank() ? "not-configured" : "failed";
                if (!replica.isBlank()) {
                    Path copy = null;
                    try {
                        Path destination = Path.of(replica).toAbsolutePath().normalize();
                        if (!Files.isDirectory(destination)) throw new IllegalStateException("Replica directory unavailable");
                        destination = destination.toRealPath();
                        if (destination.equals(root)) throw new IllegalStateException("Replica must use another directory");
                        copy = destination.resolve(name + ".partial");
                        Files.copy(archive, copy);
                        if (!hash.equals(BackupArchive.sha256(copy))) throw new IllegalStateException("Replica checksum mismatch");
                        Files.move(copy, destination.resolve(name)); copy = null; replicaState = "verified";
                        record.put("replicaState", replicaState);
                        writeRecord(destination.resolve(name + ".json"), record);
                        retain(destination);
                    } catch (Exception failure) { replicaState = "failed"; }
                    finally { if (copy != null) try { Files.deleteIfExists(copy); } catch (Exception ignored) { } }
                }
                record.put("replicaState", replicaState);
                writeRecord(root.resolve(name + ".json"), record); last = Map.copyOf(record);
                state = replicaState.equals("failed") ? "replica-failed" : "verified";
                retain(root);
            }
        } catch (Exception failure) {
            state = "failed";
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Database backup/restore verification failed: {}", failure.getClass().getSimpleName());
        } finally {
            if (partial != null) try { if (partial.getParent().equals(directory.toRealPath()) && partial.getFileName().toString().endsWith(".zip.partial")) Files.deleteIfExists(partial); } catch (Exception ignored) { }
        }
    }
    private void writeRecord(Path path, Map<String, Object> record) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".partial");
        Files.write(temporary, mapper.writeValueAsBytes(record), StandardOpenOption.CREATE_NEW);
        Files.move(temporary, path);
    }
    private com.fasterxml.jackson.databind.JsonNode ownedRecord(Path path) {
        try {
            if (Files.isSymbolicLink(path)) return null;
            var value = mapper.readTree(path.toFile()); String name = value.path("file").asText();
            if (value.path("format").asInt() != 1 || !value.path("verified").asBoolean() || !name.matches(FILE_PATTERN)
                    || !path.getFileName().toString().equals(name + ".json") || !value.path("completedAt").canConvertToLong()
                    || !value.path("sha256").asText().matches("[a-f0-9]{64}")) return null;
            Path archive = path.resolveSibling(name);
            return Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) ? value : null;
        } catch (Exception ignored) { return null; }
    }
    private void retain(Path root) {
        try (var files = Files.list(root)) {
            var records = files.filter(p -> p.getFileName().toString().matches(FILE_PATTERN + "\\.json"))
                    .filter(p -> ownedRecord(p) != null).sorted(Comparator.reverseOrder()).toList();
            for (var record : records.stream().skip(keep).toList()) {
                var value = mapper.readTree(record.toFile()); String name = value.path("file").asText();
                if (value.path("format").asInt() != 1 || !value.path("verified").asBoolean() || !name.matches(FILE_PATTERN) || !record.getFileName().toString().equals(name + ".json")) continue;
                Path archive = root.resolve(name).normalize();
                if (!archive.getParent().equals(root) || Files.isSymbolicLink(record) || Files.isSymbolicLink(archive)) continue;
                Files.deleteIfExists(archive); Files.delete(record);
            }
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Verified backup preserved; retention cleanup failed: {}", failure.getClass().getSimpleName());
        }
    }
    @PreDestroy void close() throws InterruptedException { worker.shutdown(); if (!worker.awaitTermination(300, TimeUnit.SECONDS)) worker.shutdownNow(); }
}
