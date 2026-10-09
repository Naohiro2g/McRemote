package club.code2create.mcremote;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Paper本体の旧SQLiteからclasses/native resourcesの両方を分離する。 */
final class BundledSqliteDriver {
    private static Object bridge;
    private static Method open;

    private BundledSqliteDriver() {}

    static synchronized Connection connect(String url) throws IOException, SQLException {
        if (bridge == null) {
            URL source = BundledSqliteDriver.class.getProtectionDomain().getCodeSource().getLocation();
            URL[] sources;
            try {
                // Gradle tests use unpacked classes; production uses the single plugin JAR.
                sources = Files.isDirectory(Path.of(source.toURI()))
                        ? new URL[]{source, org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation()}
                        : new URL[]{source};
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Bundled SQLite source identity is unavailable", e);
            }
            URLClassLoader loader = new URLClassLoader(sources, ClassLoader.getPlatformClassLoader());
            try {
                Class<?> type = Class.forName("club.code2create.mcremote.SQLiteDriverBridge", true, loader);
                bridge = type.getConstructor().newInstance();
                open = type.getMethod("open", String.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                try { loader.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
                throw new IOException("Bundled SQLite driver could not initialize", e);
            }
        }
        try {
            return (Connection) open.invoke(bridge, url);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sql) { throw sql; }
            throw new IOException("Bundled SQLite connection could not open", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IOException("Bundled SQLite bridge is inaccessible", e);
        }
    }
}
