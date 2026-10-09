package club.code2create.mcremote;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Filter;
import java.util.logging.Logger;

/** 同梱JARだけを読む isolated classloader から呼ぶ。親のSQLite driverを使わない。 */
public final class SQLiteDriverBridge {
    private final Driver driver;

    public SQLiteDriverBridge() throws SQLException {
        driver = new org.sqlite.JDBC();
        // JDBC's static registration would retain this loader in the global DriverManager.
        // Deregistration must run from the same loader, not from the plugin's caller loader.
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver registered = drivers.nextElement();
            if (registered.getClass().getClassLoader() == getClass().getClassLoader()) {
                DriverManager.deregisterDriver(registered);
            }
        }
    }

    public Connection open(String url) throws SQLException {
        // Upstream native-loader error messages can include private temporary paths.
        // Suppress only this thread's upstream JUL records during load; our caller logs types.
        Logger nativeLogger = Logger.getLogger("org.sqlite.SQLiteJDBCLoader");
        Filter previous = nativeLogger.getFilter();
        Thread loadingThread = Thread.currentThread();
        Filter safe = record -> Thread.currentThread() != loadingThread
                && (previous == null || previous.isLoggable(record));
        nativeLogger.setFilter(safe);
        try {
            Connection connection = driver.connect(url, new Properties());
            if (connection == null) { throw new SQLException("Bundled SQLite driver rejected URL"); }
            return connection;
        } finally {
            if (nativeLogger.getFilter() == safe) { nativeLogger.setFilter(previous); }
        }
    }
}
