package club.code2create.mcremote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 一つの復旧単位（DB＋WAL）。connection を他 backend と共有しない。 */
final class SqliteCredentialDatabase {
    @FunctionalInterface
    interface SqlAction<T> { T run(Connection connection) throws SQLException, IOException; }

    interface CommitObserver {
        default void beforeCommit() throws IOException {}
        default void beforeCommit(Connection connection) throws IOException { beforeCommit(); }
        default void afterCommit() throws IOException {}
    }

    private static final CommitObserver NO_OBSERVER = new CommitObserver() {};
    private final Path path;
    private final String role;
    private final String[] schema;

    SqliteCredentialDatabase(Path path, String role, String... schema) {
        this.path = path.toAbsolutePath().normalize();
        this.role = role;
        this.schema = schema.clone();
    }

    Path path() { return path; }

    boolean exists() throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
                if (Files.exists(Path.of(path + suffix), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("SQLite sidecar exists without its database");
                }
            }
            return false;
        }
    }

    <T> T read(SqlAction<T> action) throws IOException {
        try (Connection connection = open(false)) {
            validate(connection);
            return invoke(CredentialDiagnostics.Operation.READ_DATABASE, () -> action.run(connection));
        } catch (SQLException e) {
            throw failure(CredentialDiagnostics.Operation.CLOSE_DATABASE, e);
        }
    }

    <T> T write(SqlAction<T> action) throws IOException {
        return transaction(false, action, NO_OBSERVER);
    }

    <T> T write(SqlAction<T> action, CommitObserver observer) throws IOException {
        return transaction(false, action, observer);
    }

    <T> T createWrite(SqlAction<T> action) throws IOException {
        return transaction(true, action, NO_OBSERVER);
    }

    private <T> T transaction(boolean create, SqlAction<T> action, CommitObserver observer)
            throws IOException {
        boolean fresh = false;
        if (create && !exists()) {
            CredentialDiagnostics.run(CredentialDiagnostics.Operation.CREATE_DIRECTORY,
                    () -> Files.createDirectories(path.getParent()));
            // CREATE_NEW claims initialization. An interrupted empty DB is corruption, never absence.
            CredentialDiagnostics.run(CredentialDiagnostics.Operation.OPEN_FILE,
                    () -> Files.createFile(path));
            fresh = true;
        }
        try (Connection connection = open(fresh)) {
            boolean committed = false;
            execute(connection, "BEGIN IMMEDIATE", CredentialDiagnostics.Operation.BEGIN_DATABASE_WRITE);
            try {
                if (fresh) {
                    execute(connection, "CREATE TABLE backend_meta (id INTEGER PRIMARY KEY CHECK(id=1), role TEXT NOT NULL)",
                            CredentialDiagnostics.Operation.WRITE_DATABASE);
                    try (var statement = connection.prepareStatement("INSERT INTO backend_meta VALUES(1,?)")) {
                        statement.setString(1, role);
                        statement.executeUpdate();
                    }
                    for (String ddl : schema) {
                        execute(connection, ddl, CredentialDiagnostics.Operation.WRITE_DATABASE);
                    }
                    execute(connection, "PRAGMA user_version=1", CredentialDiagnostics.Operation.WRITE_DATABASE);
                }
                validate(connection);
                T result = invoke(CredentialDiagnostics.Operation.WRITE_DATABASE, () -> action.run(connection));
                observer.beforeCommit(connection);
                execute(connection, "COMMIT", CredentialDiagnostics.Operation.COMMIT_DATABASE);
                committed = true;
                observer.afterCommit();
                return result;
            } catch (IOException | SQLException | RuntimeException e) {
                if (!committed) {
                    try (Statement rollback = connection.createStatement()) {
                        rollback.execute("ROLLBACK");
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                }
                if (e instanceof SQLException sql) {
                    throw failure(CredentialDiagnostics.Operation.WRITE_DATABASE, sql);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw failure(CredentialDiagnostics.Operation.CLOSE_DATABASE, e);
        }
    }

    private Connection open(boolean fresh) throws IOException {
        CredentialStore.requireRegularFile(path, "SQLite credential database");
        if (Files.isSymbolicLink(path.getParent())) {
            throw new IOException("SQLite database parent must not be a symlink");
        }
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            Path sidecar = Path.of(path + suffix);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                CredentialStore.requireRegularFile(sidecar, "SQLite credential sidecar");
            }
        }
        Connection connection = invoke(CredentialDiagnostics.Operation.OPEN_DATABASE, () -> {
            return BundledSqliteDriver.connect("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=rw");
        });
        try {
            invoke(CredentialDiagnostics.Operation.CONFIGURE_DATABASE, () -> {
                try (Statement statement = connection.createStatement()) {
                    // Detect unexpected dependency/classloader substitution before touching state.
                    try (ResultSet result = statement.executeQuery("SELECT sqlite_version()")) {
                        if (!result.next() || !supportedEngine(result.getString(1))) {
                            throw new IOException("SQLite engine lacks required WAL-reset fix");
                        }
                    }
                    statement.execute("PRAGMA busy_timeout=1000");
                    if (fresh) {
                        try (ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                                throw new IOException("SQLite WAL could not be enabled");
                            }
                        }
                    }
                    statement.execute("PRAGMA synchronous=FULL");
                    try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
                        if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                            throw new IOException("SQLite journal_mode is not WAL");
                        }
                    }
                    try (ResultSet result = statement.executeQuery("PRAGMA synchronous")) {
                        if (!result.next() || result.getInt(1) != 2) {
                            throw new IOException("SQLite synchronous is not FULL");
                        }
                    }
                }
                return null;
            });
            return connection;
        } catch (IOException e) {
            try { connection.close(); } catch (SQLException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    private static boolean supportedEngine(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            return major > 3 || (major == 3 && (minor > 51 || (minor == 51 && patch >= 3)));
        } catch (RuntimeException e) { return false; }
    }

    private void validate(Connection connection) throws IOException {
        invoke(CredentialDiagnostics.Operation.VALIDATE_DATABASE, () -> {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new IOException("Unknown SQLite credential schema_version");
                    }
                }
                try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
                    if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                        throw new IOException("SQLite credential integrity check failed");
                    }
                }
                try (ResultSet result = statement.executeQuery("SELECT id,role FROM backend_meta")) {
                    if (!result.next() || result.getInt(1) != 1 || !role.equals(result.getString(2)) || result.next()) {
                        throw new IOException("SQLite credential backend role mismatch");
                    }
                }
            }
            return null;
        });
    }

    static void execute(Connection connection, String sql, CredentialDiagnostics.Operation operation)
            throws IOException {
        invoke(operation, () -> {
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            return null;
        });
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws SQLException, IOException; }

    private static <T> T invoke(CredentialDiagnostics.Operation operation, SqlSupplier<T> action)
            throws IOException {
        return CredentialDiagnostics.perform(operation, () -> {
            try { return action.get(); } catch (SQLException e) {
                throw new IOException("SQLite credential operation failed", e);
            }
        });
    }

    private static IOException failure(CredentialDiagnostics.Operation operation, SQLException e) {
        return new CredentialDiagnostics.Failure(operation, new IOException("SQLite credential operation failed", e));
    }
}
