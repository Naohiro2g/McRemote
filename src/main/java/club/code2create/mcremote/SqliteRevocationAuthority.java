package club.code2create.mcremote;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Windows authority。snapshot とは別 DB／WAL の durable commit が revoke の確定点。 */
final class SqliteRevocationAuthority extends RevocationAuthority {
    private final SqliteCredentialDatabase database;
    private final SqliteCredentialDatabase.CommitObserver observer;
    private record State(UUID domain, UUID pending) {}

    SqliteRevocationAuthority(Path databasePath) {
        this(databasePath, new SqliteCredentialDatabase.CommitObserver() {});
    }

    SqliteRevocationAuthority(Path databasePath, SqliteCredentialDatabase.CommitObserver observer) {
        super(databasePath.toAbsolutePath().normalize().getParent());
        this.observer = observer;
        database = new SqliteCredentialDatabase(databasePath, "authority",
                "CREATE TABLE authority_state (id INTEGER PRIMARY KEY CHECK(id=1), domain TEXT NOT NULL, pending TEXT, commit_marker INTEGER NOT NULL)",
                "CREATE TABLE tombstones (domain TEXT NOT NULL, id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, player_uuid TEXT NOT NULL, revoked_at TEXT NOT NULL)",
                "CREATE TABLE retired_authority (retirement_id TEXT PRIMARY KEY, domain TEXT NOT NULL, pending TEXT, commit_marker INTEGER NOT NULL)",
                "CREATE TABLE retired_tombstones (retirement_id TEXT NOT NULL, domain TEXT NOT NULL, id TEXT NOT NULL, token_hash TEXT NOT NULL, player_uuid TEXT NOT NULL, revoked_at TEXT NOT NULL, PRIMARY KEY(retirement_id,id))");
    }

    @Override Path manifestPath() { return database.path(); }

    @Override boolean manifestExists() throws IOException {
        return database.exists() && database.read(connection -> state(connection, false) != null);
    }

    @Override boolean bootstrapPendingExists() throws IOException {
        return database.read(connection -> state(connection, true).pending() != null);
    }

    @Override UUID loadManifest() throws IOException {
        return database.read(connection -> state(connection, true).domain());
    }

    @Override List<Tombstone> loadTombstones(UUID expectedDomain) throws IOException {
        return database.read(connection -> {
            State current = state(connection, true);
            if (!expectedDomain.equals(current.domain())) { throw new IOException("SQLite authority domain mismatch"); }
            return tombstones(connection, expectedDomain);
        });
    }

    @Override UUID beginBootstrap() throws IOException {
        return database.createWrite(connection -> {
            State current = state(connection, false);
            if (current != null) {
                if (current.pending() == null || !tombstones(connection, current.domain()).isEmpty()) {
                    throw new IOException("SQLite authority cannot resume bootstrap");
                }
                return current.domain();
            }
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT count(*) FROM tombstones")) {
                if (!result.next() || result.getLong(1) != 0) {
                    throw new IOException("SQLite tombstones exist without a domain");
                }
            }
            UUID domain = UUID.randomUUID();
            try (var statement = connection.prepareStatement("INSERT INTO authority_state VALUES(1,?,?,0)")) {
                statement.setString(1, domain.toString());
                statement.setString(2, UUID.randomUUID().toString());
                statement.executeUpdate();
            }
            return domain;
        });
    }

    @Override void completeBootstrap(UUID domain) throws IOException {
        database.write(connection -> {
            State current = state(connection, true);
            if (!domain.equals(current.domain()) || current.pending() == null
                    || !tombstones(connection, domain).isEmpty()) {
                throw new IOException("SQLite bootstrap completion mismatch");
            }
            SqliteCredentialDatabase.execute(connection, "UPDATE authority_state SET pending=NULL,commit_marker=commit_marker+1 WHERE id=1",
                    CredentialDiagnostics.Operation.WRITE_DATABASE);
            return null;
        });
    }

    @Override Tombstone commit(Tombstone expected) throws IOException {
        validate(expected);
        return database.write(connection -> {
            State current = state(connection, true);
            if (current.pending() != null || !expected.credentialDomainId().equals(current.domain())) {
                throw new IOException("SQLite tombstone domain is unavailable");
            }
            Tombstone existing = null;
            for (Tombstone item : tombstones(connection, current.domain())) {
                if (item.credentialId().equals(expected.credentialId())) { existing = item; }
                if (item.tokenHash().equals(expected.tokenHash()) && !item.credentialId().equals(expected.credentialId())) {
                    throw new IOException("SQLite tombstone hash contradicts another credential");
                }
            }
            if (existing != null) {
                if (!existing.equals(expected)) { throw new IOException("SQLite existing tombstone contradicts revoke request"); }
            } else {
                try (var statement = connection.prepareStatement("INSERT INTO tombstones VALUES(?,?,?,?,?)")) {
                    statement.setString(1, expected.credentialDomainId().toString());
                    statement.setString(2, expected.credentialId().toString());
                    statement.setString(3, expected.tokenHash());
                    statement.setString(4, expected.playerUuid().toString());
                    statement.setString(5, expected.revokedAt().toString());
                    statement.executeUpdate();
                }
            }
            // A retry must also perform a write commit with WAL/FULL, without changing the tombstone.
            SqliteCredentialDatabase.execute(connection, "UPDATE authority_state SET commit_marker=commit_marker+1 WHERE id=1",
                    CredentialDiagnostics.Operation.WRITE_DATABASE);
            return expected;
        }, observer);
    }

    @Override Path archive(String suffix) throws IOException {
        if (!database.exists()) { return null; }
        return database.write(connection -> {
            State current = state(connection, true);
            tombstones(connection, current.domain());
            try (var statement = connection.prepareStatement("INSERT INTO retired_authority SELECT ?,domain,pending,commit_marker FROM authority_state")) {
                statement.setString(1, suffix);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO retired_tombstones SELECT ?,domain,id,token_hash,player_uuid,revoked_at FROM tombstones")) {
                statement.setString(1, suffix);
                statement.executeUpdate();
            }
            SqliteCredentialDatabase.execute(connection, "DELETE FROM tombstones", CredentialDiagnostics.Operation.WRITE_DATABASE);
            SqliteCredentialDatabase.execute(connection, "DELETE FROM authority_state", CredentialDiagnostics.Operation.WRITE_DATABASE);
            return manifestPath();
        });
    }

    private static State state(Connection connection, boolean required) throws SQLException, IOException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id,domain,pending,commit_marker FROM authority_state")) {
            if (!result.next()) {
                if (required) { throw new IOException("SQLite authority header is missing"); }
                return null;
            }
            if (result.getInt(1) != 1 || result.getLong(4) < 0) { throw new IOException("Invalid SQLite authority header"); }
            UUID domain = parseUuid(result.getString(2), "credential_domain_id");
            String pending = result.getString(3);
            State state = new State(domain, pending == null ? null : parseUuid(pending, "transaction_id"));
            if (result.next()) { throw new IOException("Duplicate SQLite authority header"); }
            return state;
        }
    }

    private static List<Tombstone> tombstones(Connection connection, UUID domain) throws SQLException, IOException {
        List<Tombstone> items = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT domain,id,token_hash,player_uuid,revoked_at FROM tombstones")) {
            while (result.next()) {
                Instant revokedAt;
                try { revokedAt = Instant.parse(result.getString(5)); }
                catch (DateTimeParseException | NullPointerException e) {
                    throw new IOException("Invalid SQLite tombstone timestamp", e);
                }
                Tombstone item = new Tombstone(parseUuid(result.getString(1), "credential_domain_id"),
                        parseUuid(result.getString(2), "credential_id"), result.getString(3),
                        parseUuid(result.getString(4), "player_uuid"), revokedAt);
                validate(item);
                if (!domain.equals(item.credentialDomainId()) || !ids.add(item.credentialId()) || !hashes.add(item.tokenHash())) {
                    throw new IOException("SQLite authority tombstone domain or uniqueness mismatch");
                }
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static void validate(Tombstone item) throws IOException {
        if (item == null || item.credentialDomainId() == null || item.credentialId() == null
                || item.playerUuid() == null || item.revokedAt() == null
                || item.tokenHash() == null || item.tokenHash().isBlank()) {
            throw new IOException("Invalid SQLite tombstone");
        }
    }
}
