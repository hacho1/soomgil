package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class BackupTests {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    class Database implements AutoCloseable {
        final DriverManagerDataSource source;
        final JdbcTemplate jdbc;
        final Connection keeper;
        Database() throws Exception {
            source = new DriverManagerDataSource("jdbc:h2:file:" + root.resolve("live").toString().replace('\\','/') + ";DATABASE_TO_LOWER=TRUE", "sa", "");
            keeper = source.getConnection(); jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE user_accounts(id INT PRIMARY KEY, name VARCHAR(100))");
            jdbc.execute("CREATE TABLE places(id INT PRIMARY KEY, name VARCHAR(100))");
            jdbc.execute("CREATE TABLE diary_entries(id INT PRIMARY KEY, body CLOB)");
            jdbc.update("INSERT INTO user_accounts VALUES(1,'private user')");
            jdbc.update("INSERT INTO places VALUES(1,'quiet beach')");
            jdbc.update("INSERT INTO diary_entries VALUES(1,'private diary contents')");
        }
        BackupService service(String replica) { return new BackupService(source, mapper, Clock.systemUTC(), false, root.resolve("backups").toString(), replica, 2, "sa", ""); }
        public void close() throws Exception { keeper.close(); }
    }
    @Test void onlineBackupCanBeRestoredWhileTheLiveDatabaseStaysOpen() throws Exception {
        try (var db = new Database()) {
            Path replica = Files.createDirectory(root.resolve("second-disk")); var service = db.service(replica.toString());
            try {
                service.createBackup(); var status = service.status();
                assertThat(status).containsEntry("state", "verified").containsEntry("stale", false).containsEntry("replicaState", "verified");
                var record = (Map<?, ?>) status.get("last"); String name = record.get("file").toString();
                Path archive = root.resolve("backups").resolve(name);
                assertThat(BackupArchive.sha256(archive)).isEqualTo(BackupArchive.sha256(replica.resolve(name)));
                assertThat(BackupArchive.verify(archive, root.resolve("drill"), "sa", "")).containsEntry("places", 1L).containsEntry("user_accounts", 1L).containsEntry("diary_entries", 1L);
                db.jdbc.update("INSERT INTO places VALUES(2,'still writable')");
                assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM places", Integer.class)).isEqualTo(2);
                try (var files = Files.list(root.resolve("drill"))) { assertThat(files.toList()).isEmpty(); }
                assertThat(mapper.writeValueAsString(record)).doesNotContain("private user", "private diary contents");
                var restarted = db.service(replica.toString());
                try { assertThat(((Map<?, ?>) restarted.status().get("last")).get("file")).isEqualTo(name); }
                finally { restarted.close(); }
            } finally { service.close(); }
        }
    }
    @Test void disconnectedReplicaDoesNotLoseTheVerifiedLocalBackup() throws Exception {
        try (var db = new Database()) {
            var service = db.service(root.resolve("missing-external-disk").toString());
            try {
                service.createBackup();
                assertThat(service.status()).containsEntry("state", "replica-failed").containsEntry("replicaState", "failed");
                assertThat(((Map<?, ?>) service.status().get("last")).get("verified")).isEqualTo(true);
                assertThat(root.resolve("missing-external-disk")).doesNotExist();
            } finally { service.close(); }
        }
    }
    @Test void retentionOnlyRemovesOwnedBackupPairs() throws Exception {
        try (var db = new Database()) {
            var service = db.service("");
            try {
                Files.createDirectories(root.resolve("backups"));
                Path unrelated = Files.writeString(root.resolve("backups/important.zip"), "do not remove");
                service.createBackup(); service.createBackup(); service.createBackup();
                try (var files = Files.list(root.resolve("backups"))) { assertThat(files.filter(p -> p.toString().endsWith(".zip.json")).count()).isEqualTo(2); }
                assertThat(Files.readString(unrelated)).isEqualTo("do not remove");
            } finally { service.close(); }
        }
    }
    @Test void badArchivePathsAndNonDatabaseArchivesFailWithoutTouchingOutsideFiles() throws Exception {
        Path sentinel = Files.writeString(root.resolve("sentinel"), "unchanged");
        for (String name : new String[]{"../sentinel", "subdir/soomgil.mv.db", "data.sql", "C:/soomgil.mv.db"}) {
            Path zip = root.resolve("bad-" + java.util.UUID.randomUUID() + ".zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(zip))) { output.putNextEntry(new ZipEntry(name)); output.write("invalid".getBytes()); output.closeEntry(); }
            assertThatThrownBy(() -> BackupArchive.verify(zip, root.resolve("work"), "sa", "")).isInstanceOf(Exception.class);
        }
        assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
        try (var files = Files.list(root.resolve("work"))) { assertThat(files.count()).isZero(); }
    }
    @Test void damagedMetadataDoesNotHideOrDeleteHealthyBackups() throws Exception {
        try (var db = new Database()) {
            var service = db.service("");
            try {
                service.createBackup();
                Path damaged = Files.writeString(root.resolve("backups/soomgil-db-99990101T000000000Z-aaaaaaaa.zip.json"), "{broken");
                service.createBackup(); service.createBackup();
                assertThat(service.status()).containsEntry("state", "verified");
                var restarted = db.service("");
                try { assertThat((Map<?, ?>) restarted.status().get("last")).isNotEmpty(); }
                finally { restarted.close(); }
                assertThat(damaged).exists();
                try (var files = Files.list(root.resolve("backups"))) { assertThat(files.filter(p -> p.toString().endsWith(".zip")).count()).isEqualTo(2); }
            } finally { service.close(); }
        }
    }
    @Test void wrongCredentialsFailVerificationAndLeaveNoExtractedUserData() throws Exception {
        try (var db = new Database()) {
            var service = db.service("");
            try {
                service.createBackup(); String file = ((Map<?, ?>) service.status().get("last")).get("file").toString();
                assertThatThrownBy(() -> BackupArchive.verify(root.resolve("backups").resolve(file), root.resolve("drill"), "not-user", "wrong")).isInstanceOf(Exception.class);
                try (var files = Files.list(root.resolve("drill"))) { assertThat(files.count()).isZero(); }
            } finally { service.close(); }
        }
    }
    @Test void inMemoryDatabaseIsNeverPresentedAsAFileBackup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:backup-not-supported", "sa", "");
        var service = new BackupService(source, mapper, Clock.systemUTC(), true, root.toString(), "", 14, "sa", "");
        try { assertThat(service.supported()).isFalse(); assertThat(service.start()).isFalse(); }
        finally { service.close(); }
    }
}
