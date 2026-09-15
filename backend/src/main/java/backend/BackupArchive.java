package backend;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import java.util.zip.ZipFile;

/** Opens a disposable copy, never the application's live database. */
public final class BackupArchive {
    private static final long MAX_DATABASE_BYTES = 8L * 1024 * 1024 * 1024;
    private BackupArchive() { }

    public static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Map<String, Long> verify(Path archive, Path workRoot, String username, String password) throws Exception {
        Files.createDirectories(workRoot);
        Path root = workRoot.toRealPath();
        Path isolated = Files.createTempDirectory(root, "restore-check-").toRealPath();
        Path database = isolated.resolve("restored.mv.db");
        try {
            try (var zip = new ZipFile(archive.toFile())) {
                var entries = Collections.list(zip.entries());
                if (entries.size() != 1 || entries.get(0).isDirectory()
                        || !entries.get(0).getName().matches("[A-Za-z0-9_-]+\\.mv\\.db")) throw new IOException("Unsupported backup archive");
                var entry = entries.get(0);
                if (entry.getSize() <= 0 || entry.getSize() > MAX_DATABASE_BYTES || Files.getFileStore(root).getUsableSpace() < entry.getSize() + 134217728L)
                    throw new IOException("Insufficient restore space or invalid archive size");
                var crc = new java.util.zip.CRC32();
                try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(database, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int count; long written = 0;
                    while ((count = input.read(buffer)) != -1) {
                        written += count;
                        if (written > entry.getSize() || written > MAX_DATABASE_BYTES) throw new IOException("Archive size mismatch");
                        crc.update(buffer, 0, count); output.write(buffer, 0, count);
                    }
                    if (written != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Archive checksum mismatch");
                }
            }
            String location = isolated.resolve("restored").toString().replace('\\', '/');
            if (location.contains(";")) throw new IOException("Unsupported restore path");
            var counts = new TreeMap<String, Long>();
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + location + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;ACCESS_MODE_DATA=r", username, password)) {
                var tables = new ArrayList<String[]>();
                try (var names = connection.getMetaData().getTables(null, null, "%", new String[]{"BASE TABLE", "TABLE"})) {
                    while (names.next()) {
                        String schema = names.getString("TABLE_SCHEM"), table = names.getString("TABLE_NAME");
                        if ("public".equalsIgnoreCase(schema) && table.matches("[A-Za-z0-9_]+")) tables.add(new String[]{schema, table});
                    }
                }
                for (var table : tables) {
                    String quoted = "\"" + table[0] + "\".\"" + table[1] + "\"";
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(60);
                        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + quoted)) { rows.next(); counts.put(table[1].toLowerCase(Locale.ROOT), rows.getLong(1)); }
                        try (var sample = statement.executeQuery("SELECT * FROM " + quoted + " FETCH FIRST 1 ROW ONLY")) {
                            if (sample.next()) for (int i = 1; i <= sample.getMetaData().getColumnCount(); i++) sample.getObject(i);
                        }
                    }
                }
            }
            if (!counts.containsKey("user_accounts") || !counts.containsKey("places")) throw new IOException("Not a Soomgil database");
            return counts;
        } finally {
            // The only removable directory is the new, direct child created by this invocation.
            if (!isolated.getParent().equals(root) || !isolated.getFileName().toString().startsWith("restore-check-")) throw new IOException("Unsafe cleanup path");
            try (var children = Files.list(isolated)) {
                for (Path child : children.toList()) {
                    if (!child.toAbsolutePath().normalize().getParent().equals(isolated) || Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected restore output");
                    Files.deleteIfExists(child);
                }
            }
            Files.delete(isolated);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: --verify-backup <archive.zip> <private-work-directory>");
        String user = System.getenv().getOrDefault("SOOMGIL_DB_USERNAME", "sa");
        String password = System.getenv().getOrDefault("SOOMGIL_DB_PASSWORD", "");
        Path archive = Path.of(args[0]).toAbsolutePath().normalize();
        var counts = verify(archive, Path.of(args[1]), user, password);
        System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("verified", true, "sha256", sha256(archive), "tables", counts)));
    }
}
