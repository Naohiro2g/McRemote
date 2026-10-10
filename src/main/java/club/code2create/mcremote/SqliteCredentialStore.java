package club.code2create.mcremote;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 全OSのsnapshot。JSON codecは共通、publishは一つのSQL transaction。 */
final class SqliteCredentialStore extends CredentialStore {
    private final SqliteCredentialDatabase database;

    SqliteCredentialStore(Path path) {
        super(path);
        database = new SqliteCredentialDatabase(path, "snapshot",
                "CREATE TABLE snapshot_state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)",
                "CREATE TABLE retired_snapshots (retirement_id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
    }

    @Override boolean exists() throws IOException {
        if (!database.exists()) { return false; }
        return database.read(connection -> payload(connection) != null);
    }

    @Override LoadedSnapshot load() throws IOException {
        return database.read(connection -> {
            String json = payload(connection);
            if (json == null) { throw new IOException("SQLite snapshot header is missing"); }
            return decode(json);
        });
    }

    @Override void initialize(UUID domainId) throws IOException {
        String json = encode(domainId, List.of());
        database.createWrite(connection -> {
            if (payload(connection) != null) { throw new IOException("SQLite snapshot already initialized"); }
            try (var statement = connection.prepareStatement("INSERT INTO snapshot_state VALUES(1,?)")) {
                statement.setString(1, json);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override void persist(UUID domainId, List<CredentialRecord> records) throws IOException {
        String json = encode(domainId, records);
        decode(json); // Keep the same persisted-record validation before commit.
        database.write(connection -> {
            String previous = payload(connection);
            if (previous == null || !domainId.equals(decode(previous).credentialDomainId())) {
                throw new IOException("SQLite snapshot disappeared or changed domain");
            }
            try (var statement = connection.prepareStatement("UPDATE snapshot_state SET payload=? WHERE id=1")) {
                statement.setString(1, json);
                if (statement.executeUpdate() != 1) { throw new IOException("SQLite snapshot update failed"); }
            }
            return null;
        });
    }

    @Override Path archive(String suffix) throws IOException {
        if (!database.exists()) { return null; }
        return database.write(connection -> {
            String json = payload(connection);
            if (json == null) { throw new IOException("Cannot retire a SQLite snapshot without its header"); }
            decode(json);
            try (var statement = connection.prepareStatement("INSERT INTO retired_snapshots VALUES(?,?)")) {
                statement.setString(1, suffix);
                statement.setString(2, json);
                statement.executeUpdate();
            }
            SqliteCredentialDatabase.execute(connection, "DELETE FROM snapshot_state",
                    CredentialDiagnostics.Operation.WRITE_DATABASE);
            // Retain previous state durably inside the same DB/WAL bundle; no filesystem rename.
            return path();
        });
    }

    private static String payload(Connection connection) throws SQLException, IOException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id,payload FROM snapshot_state")) {
            if (!result.next()) { return null; }
            if (result.getInt(1) != 1 || result.getString(2) == null) {
                throw new IOException("Invalid SQLite snapshot header");
            }
            String json = result.getString(2);
            if (result.next()) { throw new IOException("Duplicate SQLite snapshot header"); }
            return json;
        }
    }
}
