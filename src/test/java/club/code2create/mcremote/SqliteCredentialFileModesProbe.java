package club.code2create.mcremote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/** Runs only with isolated, generated credentials; never prints their values or paths. */
public final class SqliteCredentialFileModesProbe {
    private static final String PRIVATE_FILE = "rw-------";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        // Represents an existing shared parent/volume root; it must remain unchanged.
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxr-x"));
        Path snapshot = root.resolve("store/snapshot.json");
        Path authority = root.resolve("authority");
        Path db = snapshot.resolveSibling("snapshot.json.sqlite");
        Path authorityDb = root.resolve("authority-sqlite/authority.sqlite");
        try (var service = new CredentialService(snapshot, authority, 16)) {
            healthy(service);
            if (args[1].equals("create")) {
                mode(db.getParent(), "rwx------", "new snapshot directory");
                mode(authorityDb.getParent(), "rwx------", "new authority directory");
                bundle(db); bundle(authorityDb);
                System.out.println("PASS create directories and DBs");
            }
            // Keep a second connection alive so existing WAL and SHM cannot disappear on close.
            try (Connection held = PaperSqliteDriver.connect("jdbc:sqlite:" + db.toUri() + "?mode=rw");
                 Connection heldAuthority = PaperSqliteDriver.connect("jdbc:sqlite:" + authorityDb.toUri() + "?mode=rw");
                 var statement = held.createStatement(); var authorityStatement = heldAuthority.createStatement()) {
                statement.execute("UPDATE snapshot_state SET payload=payload");
                authorityStatement.execute("UPDATE authority_state SET commit_marker=commit_marker");
                if (args[1].equals("existing")) {
                    for (Path database : List.of(db, authorityDb)) {
                        Files.setPosixFilePermissions(database.getParent(), PosixFilePermissions.fromString("rwxrwxr-x"));
                        // Synthetic empty journal, not a live SQLite recovery file.
                        Files.createFile(Path.of(database + "-journal"));
                        for (Path file : files(database)) {
                            if (Files.exists(file)) Files.setPosixFilePermissions(file,
                                    PosixFilePermissions.fromString("rw-rw-r--"));
                        }
                    }
                    try (var restarted = new CredentialService(snapshot, authority, 16)) { healthy(restarted); }
                    bundle(db); bundle(authorityDb);
                    mode(db.getParent(), "rwxrwxr-x", "existing directory");
                    mode(authorityDb.getParent(), "rwxrwxr-x", "existing directory");
                    System.out.println("PASS existing DB/WAL/SHM/journal at startup");
                }
                UUID player = UUID.randomUUID();
                var issued = service.issue(player, "synthetic");
                service.resolveAndTouch(issued.token());
                for (var item : List.of(new Object[]{db, "snapshot"}, new Object[]{authorityDb, "authority"})) {
                    Path file = (Path)item[0];
                    new SqliteCredentialDatabase(file, (String)item[1]).write(connection -> {
                        SqliteCredentialDatabase.execute(connection,
                                item[1].equals("snapshot") ? "UPDATE snapshot_state SET payload=payload"
                                        : "UPDATE authority_state SET commit_marker=commit_marker",
                                CredentialDiagnostics.Operation.WRITE_DATABASE);
                        bundle(file); // Observe sidecars while the actual backend transaction is open.
                        return null;
                    });
                }
                bundle(db); bundle(authorityDb);
                System.out.println("PASS update and open sidecars");
                if (args[1].equals("existing")) {
                    for (var item : List.of(new Object[]{db, "snapshot"}, new Object[]{authorityDb, "authority"})) {
                        Path database = (Path)item[0];
                        for (Path file : files(database)) if (Files.exists(file)) {
                            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
                        }
                        new SqliteCredentialDatabase(database, (String)item[1]).read(connection -> {
                            bundle(database); return null;
                        });
                        Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("r--------"));
                        new SqliteCredentialDatabase(database, (String)item[1]).write(connection -> {
                            bundle(database); return null;
                        });
                    }
                    System.out.println("PASS existing bundle at reopen");
                }
                service.reset();
                bundle(db); bundle(authorityDb);
                System.out.println("PASS reset");
            }
        }
        // Missing backend causes reinitialization; newly recreated DB remains private.
        // Remove only our artificial empty journals, after every connection is closed.
        for (Path database : List.of(db, authorityDb)) Files.deleteIfExists(Path.of(database + "-journal"));
        Files.delete(db);
        try (var recreated = new CredentialService(snapshot, authority, 16)) { healthy(recreated); }
        bundle(db); bundle(authorityDb);
        mode(root, "rwxrwxr-x", "existing shared directory");
        System.out.println("PASS recreate and unchanged shared parent");
    }

    private static List<Path> files(Path db) {
        return List.of(db, Path.of(db + "-wal"), Path.of(db + "-shm"), Path.of(db + "-journal"));
    }

    private static void bundle(Path db) throws java.io.IOException {
        for (Path path : files(db)) if (Files.exists(path)) mode(path, PRIVATE_FILE, "DB or sidecar");
    }

    private static void mode(Path path, String expected, String label) throws java.io.IOException {
        String actual = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        if (!actual.equals(expected)) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }

    private static void healthy(CredentialService service) {
        if (service.health() != CredentialService.Health.HEALTHY) {
            throw new AssertionError("credential backend must initialize: " + service.healthDetail());
        }
    }
}
