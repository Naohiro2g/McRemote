package club.code2create.mcremote;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Filter;
import java.util.logging.Logger;

/** Paperが提供するJDBCを使う。独自loader・native・downloadを持たない。 */
final class PaperSqliteDriver {
    private static final Logger LOGGER = Logger.getLogger("McR_CredentialService");
    private static final Set<String> REPORTED = new HashSet<>();
    private static Driver driver;

    private PaperSqliteDriver() {}

    static Driver loadDriver(ClassLoader loader) throws IOException {
        try {
            return (Driver) Class.forName("org.sqlite.JDBC", true, loader)
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new IOException("Paper SQLite JDBC provider is unavailable", e);
        }
    }

    // Serialize our loader-log filter changes; Paper's shared DriverManager is untouched.
    static synchronized Connection connect(String url) throws IOException, SQLException {
        if (driver == null) {
            driver = CredentialDiagnostics.perform(CredentialDiagnostics.Operation.SQLITE_PROVIDER,
                    () -> loadDriver(PaperSqliteDriver.class.getClassLoader()));
        }
        Logger nativeLogger = Logger.getLogger("org.sqlite.SQLiteJDBCLoader");
        Filter previous = nativeLogger.getFilter();
        Thread loadingThread = Thread.currentThread();
        Filter safe = record -> Thread.currentThread() != loadingThread
                && (previous == null || previous.isLoggable(record));
        nativeLogger.setFilter(safe);
        try {
            Connection connection = driver.connect(url, new Properties());
            if (connection == null) { throw new SQLException("Paper SQLite JDBC rejected URL"); }
            return connection;
        } catch (LinkageError e) {
            throw new IOException("Paper SQLite native provider could not initialize", e);
        } finally {
            if (nativeLogger.getFilter() == safe) { nativeLogger.setFilter(previous); }
        }
    }

    static synchronized void reportProvider(Connection connection, String engine) throws SQLException {
        String source = "unknown";
        var codeSource = driver.getClass().getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            try { source = Path.of(codeSource.getLocation().toURI()).getFileName().toString(); }
            catch (Exception ignored) { /* Never log private URI/path or exception messages. */ }
        }
        String identity = "SQLite provider: class=" + driver.getClass().getName()
                + " source=" + source + " jdbc=" + connection.getMetaData().getDriverVersion()
                + " engine=" + engine;
        if (REPORTED.add(identity)) { LOGGER.info(identity); }
    }
}
