package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 管理用 credential snapshot。失効の security 正本は {@link RevocationAuthority}。 */
class CredentialStore {
    static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    record CredentialRecord(UUID credentialId, String tokenHash, UUID playerUuid, String type,
                            String device, Instant issuedAt, Instant lastUsedAt,
                            Instant expiresAt, Instant revokedAt) {
        CredentialRecord withLastUsedAt(Instant value) {
            return new CredentialRecord(credentialId, tokenHash, playerUuid, type, device,
                    issuedAt, value, expiresAt, revokedAt);
        }

        CredentialRecord withRevokedAt(Instant value) {
            return new CredentialRecord(credentialId, tokenHash, playerUuid, type, device,
                    issuedAt, lastUsedAt, expiresAt, value);
        }
    }

    record LoadedSnapshot(UUID credentialDomainId, List<CredentialRecord> records) {}

    private static final class SnapshotDocument {
        int schema_version;
        String credential_domain_id;
        List<RecordDocument> records;
    }

    private static final class RecordDocument {
        String credential_id;
        String token_hash;
        String player_uuid;
        String type;
        String device;
        String issued_at;
        String last_used_at;
        String expires_at;
        String revoked_at;
    }

    private final Path path;

    CredentialStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    LoadedSnapshot load() throws IOException {
        requireRegularFile(path, "credential snapshot");
        SnapshotDocument doc;
        try {
            doc = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SnapshotDocument.class);
        } catch (JsonParseException e) {
            throw new IOException("Credential snapshot is not valid JSON", e);
        }
        if (doc == null || doc.schema_version != SCHEMA_VERSION) {
            throw new IOException("Unknown credential snapshot schema_version");
        }
        UUID domain = parseUuid(doc.credential_domain_id, "credential_domain_id");
        if (doc.records == null) {
            throw new IOException("Credential snapshot records is missing");
        }

        List<CredentialRecord> records = new ArrayList<>(doc.records.size());
        Set<UUID> ids = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (RecordDocument item : doc.records) {
            CredentialRecord record = fromDocument(item);
            if (!ids.add(record.credentialId())) {
                throw new IOException("Duplicate credential_id in snapshot: " + record.credentialId());
            }
            if (!hashes.add(record.tokenHash())) {
                throw new IOException("Duplicate token_hash in snapshot");
            }
            records.add(record);
        }
        return new LoadedSnapshot(domain, List.copyOf(records));
    }

    void initialize(UUID domainId) throws IOException {
        if (exists()) {
            throw new IOException("Credential snapshot already exists: " + path);
        }
        writeSnapshot(domainId, List.of(), false);
    }

    void persist(UUID domainId, List<CredentialRecord> records) throws IOException {
        writeSnapshot(domainId, records, true);
    }

    private void writeSnapshot(UUID domainId, List<CredentialRecord> records,
                               boolean requireExisting) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Credential snapshot has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) {
            throw new IOException("Credential snapshot parent must not be a symlink: " + parent);
        }
        if (requireExisting && !exists()) {
            throw new IOException("Credential snapshot disappeared: " + path);
        }
        if (exists()) {
            requireRegularFile(path, "credential snapshot");
        }

        SnapshotDocument doc = new SnapshotDocument();
        doc.schema_version = SCHEMA_VERSION;
        doc.credential_domain_id = domainId.toString();
        doc.records = records.stream().map(CredentialStore::toDocument).toList();
        byte[] bytes = (GSON.toJson(doc) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temp = parent.resolve("." + path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            writeNewAndForce(temp, bytes);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Credential snapshot filesystem does not support atomic replace", e);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    Path archive(String suffix) throws IOException {
        if (!exists()) {
            return null;
        }
        requireRegularFile(path, "credential snapshot");
        Path archived = path.resolveSibling(path.getFileName() + ".retired-" + suffix);
        Files.move(path, archived, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(path.getParent());
        return archived;
    }

    static void writeNewAndForce(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static void requireRegularFile(Path candidate, String label) throws IOException {
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular non-symlink file: " + candidate);
        }
    }

    private static CredentialRecord fromDocument(RecordDocument item) throws IOException {
        if (item == null) {
            throw new IOException("Null credential record");
        }
        UUID credentialId = parseUuid(item.credential_id, "credential_id");
        UUID playerUuid = parseUuid(item.player_uuid, "player_uuid");
        if (item.token_hash == null || item.token_hash.isBlank()) {
            throw new IOException("Credential token_hash is missing");
        }
        if (!"long_lived".equals(item.type)) {
            throw new IOException("Unknown persisted credential type: " + item.type);
        }
        if (item.device != null) {
            int length = item.device.codePointCount(0, item.device.length());
            if (!item.device.equals(item.device.trim()) || length < 1 || length > 64) {
                throw new IOException("Invalid persisted device label");
            }
        }
        return new CredentialRecord(credentialId, item.token_hash, playerUuid, item.type,
                item.device, parseInstant(item.issued_at, "issued_at", false),
                parseInstant(item.last_used_at, "last_used_at", false),
                parseInstant(item.expires_at, "expires_at", true),
                parseInstant(item.revoked_at, "revoked_at", true));
    }

    private static RecordDocument toDocument(CredentialRecord record) {
        RecordDocument item = new RecordDocument();
        item.credential_id = record.credentialId().toString();
        item.token_hash = record.tokenHash();
        item.player_uuid = record.playerUuid().toString();
        item.type = record.type();
        item.device = record.device();
        item.issued_at = record.issuedAt().toString();
        item.last_used_at = record.lastUsedAt().toString();
        item.expires_at = record.expiresAt() == null ? null : record.expiresAt().toString();
        item.revoked_at = record.revokedAt() == null ? null : record.revokedAt().toString();
        return item;
    }

    private static UUID parseUuid(String value, String field) throws IOException {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid canonical UUID in " + field, e);
        }
    }

    private static Instant parseInstant(String value, String field, boolean nullable) throws IOException {
        if (value == null && nullable) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IOException("Invalid UTC timestamp in " + field, e);
        }
    }
}
