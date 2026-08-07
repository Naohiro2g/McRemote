package club.code2create.mcremote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** create-only tombstone を持つ失効の security 正本。 */
class RevocationAuthority {
    private static final Logger LOGGER = Logger.getLogger("McR_RevocationAuthority");
    static final int SCHEMA_VERSION = 1;
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String BOOTSTRAP_MARKER = ".bootstrap-pending.json";
    private static final String TOMBSTONE_PREFIX = "revoked-";
    private static final String TOMBSTONE_SUFFIX = ".json";
    private static final String TEMP_PREFIX = ".revoke-tmp-";
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    record Tombstone(UUID credentialDomainId, UUID credentialId, String tokenHash,
                     UUID playerUuid, Instant revokedAt) {}

    private static final class ManifestDocument {
        int schema_version;
        String credential_domain_id;
    }

    private static final class BootstrapDocument {
        int schema_version;
        String credential_domain_id;
        String transaction_id;
    }

    private static final class TombstoneDocument {
        int schema_version;
        String credential_domain_id;
        String credential_id;
        String token_hash;
        String player_uuid;
        String revoked_at;
    }

    private final Path directory;

    RevocationAuthority(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    Path path() {
        return directory;
    }

    Path manifestPath() {
        return directory.resolve(MANIFEST_NAME);
    }

    boolean directoryExists() {
        return Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
    }

    boolean manifestExists() {
        return Files.exists(manifestPath(), LinkOption.NOFOLLOW_LINKS);
    }

    boolean bootstrapPendingExists() {
        return Files.exists(directory.resolve(BOOTSTRAP_MARKER), LinkOption.NOFOLLOW_LINKS);
    }

    UUID loadManifest() throws IOException {
        requireDirectory();
        Path manifest = manifestPath();
        CredentialStore.requireRegularFile(manifest, "revocation authority manifest");
        ManifestDocument doc;
        try {
            doc = GSON.fromJson(Files.readString(manifest, StandardCharsets.UTF_8), ManifestDocument.class);
        } catch (JsonParseException e) {
            throw new IOException("Revocation authority manifest is not valid JSON", e);
        }
        if (doc == null || doc.schema_version != SCHEMA_VERSION) {
            throw new IOException("Unknown revocation authority manifest schema_version");
        }
        return parseUuid(doc.credential_domain_id, "credential_domain_id");
    }

    List<Tombstone> loadTombstones(UUID expectedDomain) throws IOException {
        requireDirectory();
        List<Tombstone> tombstones = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path item : stream) {
                String name = item.getFileName().toString();
                if (MANIFEST_NAME.equals(name) || BOOTSTRAP_MARKER.equals(name)
                        || name.startsWith(TEMP_PREFIX)) {
                    continue;
                }
                if (!name.startsWith(TOMBSTONE_PREFIX) || !name.endsWith(TOMBSTONE_SUFFIX)) {
                    throw new IOException("Unexpected file in revocation authority: " + item);
                }
                CredentialStore.requireRegularFile(item, "revocation tombstone");
                Tombstone tombstone = readTombstone(item);
                String canonicalName = tombstoneFileName(tombstone.credentialId());
                if (!canonicalName.equals(name)) {
                    throw new IOException("Tombstone filename does not match credential_id: " + item);
                }
                if (!expectedDomain.equals(tombstone.credentialDomainId())) {
                    throw new IOException("Tombstone credential_domain_id mismatch: " + item);
                }
                tombstones.add(tombstone);
            }
        }
        return List.copyOf(tombstones);
    }

    /**
     * 明示 bootstrap。manifest を先に作り、snapshot 作成前の crash は marker により同一 transaction
     * と判定して再試行できる。通常起動からは呼ばない。
     */
    UUID beginBootstrap() throws IOException {
        if (!directoryExists()) {
            Files.createDirectories(directory);
            CredentialStore.forceDirectory(directory.getParent());
        }
        requireDirectory();

        Path marker = directory.resolve(BOOTSTRAP_MARKER);
        if (manifestExists()) {
            UUID domain = loadManifest();
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Authority already initialized without a pending bootstrap transaction");
            }
            BootstrapDocument pending = readBootstrapMarker(marker);
            if (!domain.toString().equals(pending.credential_domain_id)) {
                throw new IOException("Bootstrap marker domain does not match authority manifest");
            }
            if (!loadTombstones(domain).isEmpty()) {
                throw new IOException("Cannot resume bootstrap after tombstones exist");
            }
            return domain;
        }

        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bootstrap marker exists without authority manifest");
        }
        if (hasUnexpectedBootstrapContent()) {
            throw new IOException("Authority directory is not empty before bootstrap");
        }

        UUID domain = UUID.randomUUID();
        BootstrapDocument pending = new BootstrapDocument();
        pending.schema_version = SCHEMA_VERSION;
        pending.credential_domain_id = domain.toString();
        pending.transaction_id = UUID.randomUUID().toString();
        writeCreateOnly(marker, GSON.toJson(pending) + "\n");

        ManifestDocument manifest = new ManifestDocument();
        manifest.schema_version = SCHEMA_VERSION;
        manifest.credential_domain_id = domain.toString();
        writeCreateOnly(manifestPath(), GSON.toJson(manifest) + "\n");
        CredentialStore.forceDirectory(directory);
        return domain;
    }

    void completeBootstrap(UUID domain) throws IOException {
        UUID manifestDomain = loadManifest();
        if (!domain.equals(manifestDomain)) {
            throw new IOException("Cannot complete bootstrap for a different domain");
        }
        Path marker = directory.resolve(BOOTSTRAP_MARKER);
        BootstrapDocument pending = readBootstrapMarker(marker);
        if (!domain.toString().equals(pending.credential_domain_id)) {
            throw new IOException("Bootstrap marker domain mismatch");
        }
        Files.delete(marker);
        CredentialStore.forceDirectory(directory);
    }

    /**
     * tombstone を非上書きで publish し directory fsync まで完了する。ここが revoke 線形化点。
     */
    Tombstone commit(Tombstone tombstone) throws IOException {
        UUID manifestDomain = loadManifest();
        if (!manifestDomain.equals(tombstone.credentialDomainId())) {
            throw new IOException("Tombstone domain does not match authority manifest");
        }
        Path finalPath = directory.resolve(tombstoneFileName(tombstone.credentialId()));
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            Tombstone existing = readAndValidateExisting(finalPath, tombstone);
            CredentialStore.forceDirectory(directory);
            return existing;
        }

        TombstoneDocument doc = toDocument(tombstone);
        byte[] bytes = (GSON.toJson(doc) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temp = directory.resolve(TEMP_PREFIX + UUID.randomUUID());
        Tombstone committed;
        try {
            CredentialStore.writeNewAndForce(temp, bytes);
            try {
                // hard-link publish は同一 directory 内で atomic かつ target 非存在を要求する。
                Files.createLink(finalPath, temp);
                committed = tombstone;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                committed = readAndValidateExisting(finalPath, tombstone);
            }
            // この fsync 完了が線形化点。これ以降の cleanup failure で失敗へ戻さない。
            CredentialStore.forceDirectory(directory);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupError) {
                LOGGER.warning("Could not remove interrupted revoke temp file " + temp
                        + ": " + cleanupError.getMessage());
            }
        }
        return committed;
    }

    Path archive(String suffix) throws IOException {
        if (!directoryExists()) {
            return null;
        }
        requireDirectory();
        Path archived = directory.resolveSibling(directory.getFileName() + ".retired-" + suffix);
        Files.move(directory, archived, StandardCopyOption.ATOMIC_MOVE);
        CredentialStore.forceDirectory(directory.getParent());
        return archived;
    }

    private Tombstone readAndValidateExisting(Path finalPath, Tombstone expected) throws IOException {
        CredentialStore.requireRegularFile(finalPath, "revocation tombstone");
        Tombstone existing = readTombstone(finalPath);
        if (!existing.equals(expected)) {
            throw new IOException("Existing tombstone contradicts revoke request: " + finalPath);
        }
        return existing;
    }

    private Tombstone readTombstone(Path path) throws IOException {
        TombstoneDocument doc;
        try {
            doc = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), TombstoneDocument.class);
        } catch (JsonParseException e) {
            throw new IOException("Revocation tombstone is not valid JSON: " + path, e);
        }
        if (doc == null || doc.schema_version != SCHEMA_VERSION) {
            throw new IOException("Unknown tombstone schema_version: " + path);
        }
        if (doc.token_hash == null || doc.token_hash.isBlank()) {
            throw new IOException("Tombstone token_hash is missing: " + path);
        }
        Instant revokedAt;
        try {
            revokedAt = Instant.parse(doc.revoked_at);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IOException("Invalid tombstone revoked_at: " + path, e);
        }
        return new Tombstone(parseUuid(doc.credential_domain_id, "credential_domain_id"),
                parseUuid(doc.credential_id, "credential_id"), doc.token_hash,
                parseUuid(doc.player_uuid, "player_uuid"), revokedAt);
    }

    private BootstrapDocument readBootstrapMarker(Path marker) throws IOException {
        CredentialStore.requireRegularFile(marker, "bootstrap marker");
        BootstrapDocument doc;
        try {
            doc = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8), BootstrapDocument.class);
        } catch (JsonParseException e) {
            throw new IOException("Bootstrap marker is not valid JSON", e);
        }
        if (doc == null || doc.schema_version != SCHEMA_VERSION) {
            throw new IOException("Unknown bootstrap marker schema_version");
        }
        parseUuid(doc.credential_domain_id, "credential_domain_id");
        parseUuid(doc.transaction_id, "transaction_id");
        return doc;
    }

    private boolean hasUnexpectedBootstrapContent() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return stream.iterator().hasNext();
        }
    }

    private void requireDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Revocation authority must be a non-symlink directory: " + directory);
        }
    }

    private static void writeCreateOnly(Path target, String json) throws IOException {
        CredentialStore.writeNewAndForce(target, json.getBytes(StandardCharsets.UTF_8));
        CredentialStore.forceDirectory(target.getParent());
    }

    private static String tombstoneFileName(UUID credentialId) {
        return TOMBSTONE_PREFIX + credentialId + TOMBSTONE_SUFFIX;
    }

    private static TombstoneDocument toDocument(Tombstone tombstone) {
        TombstoneDocument doc = new TombstoneDocument();
        doc.schema_version = SCHEMA_VERSION;
        doc.credential_domain_id = tombstone.credentialDomainId().toString();
        doc.credential_id = tombstone.credentialId().toString();
        doc.token_hash = tombstone.tokenHash();
        doc.player_uuid = tombstone.playerUuid().toString();
        doc.revoked_at = tombstone.revokedAt().toString();
        return doc;
    }

    private static UUID parseUuid(String value, String field) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid canonical UUID in " + field, e);
        }
    }
}
