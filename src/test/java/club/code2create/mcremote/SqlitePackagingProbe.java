package club.code2create.mcremote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/** Paper相当の旧JDBCを親classpathの先頭、配布JARを続けて置いてnative/loaderを検証する。 */
public final class SqlitePackagingProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcremote-sqlite-packaging-");
        try {
            Class.forName("org.sqlite.JDBC");
            String parent;
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("parent.sqlite"));
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT sqlite_version()")) {
                if (!result.next()) { throw new AssertionError("parent version missing"); }
                parent = result.getString(1);
            }
            if (!"3.49.1".equals(parent)) { throw new AssertionError("parent fixture is not Paper's old engine"); }
            Path snapshot = root.resolve("snapshot.json");
            Path authority = root.resolve("authority");
            CredentialService service = CredentialService.forOperatingSystem(snapshot, authority, 16, "Windows 11");
            if (service.health() != CredentialService.Health.HEALTHY) {
                throw new AssertionError("packaged SQLite backend failed: " + service.healthDetail());
            }
            Path snapshotDb = root.resolve("snapshot.json.sqlite");
            String bundled = new SqliteCredentialDatabase(snapshotDb, "snapshot").read(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT sqlite_version()")) {
                    if (!result.next()) { throw new AssertionError("bundled version missing"); }
                    return result.getString(1);
                }
            });
            if (!"3.53.4".equals(bundled)) { throw new AssertionError("bundled engine identity mismatch"); }
            UUID player = UUID.randomUUID();
            var issued = service.issue(player, "packaging probe");
            service.resolveAndTouch(issued.token());
            service.revoke(player, issued.credentialId());
            CredentialService restarted = CredentialService.forOperatingSystem(snapshot, authority, 16, "Windows 11");
            if (restarted.resolveAndTouch(issued.token()).status() != CredentialService.ResolveStatus.REVOKED) {
                throw new AssertionError("packaged durable revoke did not survive restart");
            }
            System.out.println("parent_sqlite_version=" + parent);
            System.out.println("bundled_sqlite_version=" + bundled);
            System.out.println("status=PASS");
        } finally {
            // All DB connections have closed, so removal includes every native WAL sidecar.
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
