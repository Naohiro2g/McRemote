package club.code2create.mcremote;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * session token と long-lived credential の永続 lifecycle。
 * long-lived credential だけは snapshot と authority を常に重ねて判断する。
 */
public class CredentialService {
    private static final Logger LOGGER = Logger.getLogger("McR_CredentialService");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    public enum Health {
        UNINITIALIZED,
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public enum ResolveStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        NOT_FOUND
    }

    public record IssueResult(String token, UUID credentialId) {}

    public record ResolveResult(ResolveStatus status, CredentialStore.CredentialRecord record) {}

    public record ListedCredential(UUID credentialId, String type, String device,
                                   Instant issuedAt, Instant lastUsedAt, Instant expiresAt) {}

    public record RevokeResult(UUID credentialId, boolean projectionUpdated) {}

    public record ResetResult(UUID credentialDomainId, java.nio.file.Path archivedSnapshot,
                              java.nio.file.Path archivedAuthority) {}

    private final CredentialStore store;
    private final RevocationAuthority authority;
    private final int activeLimit;
    private final Map<UUID, CredentialStore.CredentialRecord> recordsById = new HashMap<>();
    private final Map<String, CredentialStore.CredentialRecord> recordsByHash = new HashMap<>();
    private final Map<UUID, RevocationAuthority.Tombstone> tombstonesById = new HashMap<>();
    private final Map<String, RevocationAuthority.Tombstone> tombstonesByHash = new HashMap<>();

    private UUID domainId;
    private Health health;
    private String healthDetail;

    public CredentialService(java.nio.file.Path snapshotPath, java.nio.file.Path authorityPath,
                             int activeLimit) {
        this(new CredentialStore(snapshotPath), new RevocationAuthority(authorityPath), activeLimit);
    }

    CredentialService(CredentialStore store, RevocationAuthority authority, int activeLimit) {
        this.store = store;
        this.authority = authority;
        this.activeLimit = Math.max(1, activeLimit);
        try {
            validatePaths();
            loadCurrentState();
        } catch (IOException | RuntimeException e) {
            markUnhealthy("Credential domain could not be trusted", e);
        }
    }

    public synchronized Health health() {
        return health;
    }

    public synchronized String healthDetail() {
        return healthDetail;
    }

    public synchronized UUID credentialDomainId() {
        return domainId;
    }

    public int activeLimit() {
        return activeLimit;
    }

    /** 明示 bootstrap。通常起動からは呼ばない。 */
    public synchronized UUID bootstrap() throws IOException {
        validatePaths();
        if (health == Health.HEALTHY || health == Health.DEGRADED) {
            throw new IOException("Credential domain is already initialized");
        }
        boolean snapshotExists = store.exists();
        boolean manifestExists = authority.manifestExists();
        if (snapshotExists && !manifestExists) {
            throw new IOException("Snapshot exists without revocation authority; refusing bootstrap");
        }

        UUID domain = authority.beginBootstrap();
        if (snapshotExists) {
            CredentialStore.LoadedSnapshot loaded = store.load();
            if (!domain.equals(loaded.credentialDomainId()) || !loaded.records().isEmpty()) {
                throw new IOException("Existing snapshot is not a safe empty bootstrap continuation");
            }
        } else {
            store.initialize(domain);
        }
        authority.completeBootstrap(domain);
        loadCurrentState();
        if (health != Health.HEALTHY) {
            throw new IOException("Credential bootstrap did not produce a healthy domain: " + healthDetail);
        }
        return domain;
    }

    /** 全 credential を失効させる明示 reset。旧 state は削除せず sibling archive へ退避する。 */
    public synchronized ResetResult reset() throws IOException {
        String suffix = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        java.nio.file.Path archivedAuthority = null;
        java.nio.file.Path archivedSnapshot = null;
        try {
            archivedAuthority = authority.archive(suffix);
            archivedSnapshot = store.archive(suffix);
            clearMemory();
            health = Health.UNINITIALIZED;
            healthDetail = "Explicit reset is creating a new credential domain";
            UUID newDomain = bootstrap();
            return new ResetResult(newDomain, archivedSnapshot, archivedAuthority);
        } catch (IOException e) {
            markUnhealthy("Explicit credential reset stopped in a fail-closed intermediate state", e);
            throw e;
        }
    }

    public synchronized IssueResult issue(UUID playerUuid, String device)
            throws CredentialStoreUnavailableException, CredentialLimitReachedException {
        requireUsable(CredentialStoreUnavailableException.Operation.ISSUE);
        if (health == Health.DEGRADED) {
            throw unavailable(CredentialStoreUnavailableException.Operation.ISSUE,
                    "Snapshot projection is degraded; reconcile before issuing credentials", null);
        }
        int active = activeCount(playerUuid);
        if (active >= activeLimit) {
            throw new CredentialLimitReachedException(activeLimit, active);
        }

        return issueRecord(playerUuid, normalizeDevice(device),
                CredentialStore.TYPE_LONG_LIVED, "mcrl_", null);
    }

    public synchronized IssueResult issueSession(UUID playerUuid, String device,
                                                  long ttlSeconds)
            throws CredentialStoreUnavailableException {
        requireUsable(CredentialStoreUnavailableException.Operation.ISSUE);
        if (health == Health.DEGRADED) {
            throw unavailable(CredentialStoreUnavailableException.Operation.ISSUE,
                    "Snapshot projection is degraded; reconcile before issuing credentials", null);
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("session token TTL must be positive");
        }
        Instant now = Instant.now();
        return issueRecord(playerUuid, normalizeDevice(device), CredentialStore.TYPE_SESSION,
                "mcrs_", now.plusSeconds(ttlSeconds), now);
    }

    private IssueResult issueRecord(UUID playerUuid, String device, String type,
                                    String prefix, Instant expiresAt) throws CredentialStoreUnavailableException {
        return issueRecord(playerUuid, device, type, prefix, expiresAt, Instant.now());
    }

    private IssueResult issueRecord(UUID playerUuid, String device, String type,
                                    String prefix, Instant expiresAt, Instant now)
            throws CredentialStoreUnavailableException {

        String raw;
        String tokenHash;
        do {
            byte[] body = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(body);
            raw = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(body);
            tokenHash = TokenStore.hash(raw);
        } while (recordsByHash.containsKey(tokenHash) || tombstonesByHash.containsKey(tokenHash));
        UUID credentialId;
        do {
            credentialId = UUID.randomUUID();
        } while (recordsById.containsKey(credentialId) || tombstonesById.containsKey(credentialId));
        CredentialStore.CredentialRecord record = new CredentialStore.CredentialRecord(
                credentialId, tokenHash, playerUuid, type,
                device, now, now, expiresAt, null);
        List<CredentialStore.CredentialRecord> next = new ArrayList<>(recordsById.values());
        next.add(record);
        try {
            store.persist(domainId, sorted(next));
        } catch (IOException e) {
            markUnhealthy("Issued credential snapshot result is uncertain", e);
            throw unavailable(CredentialStoreUnavailableException.Operation.ISSUE,
                    healthDetail, e);
        }
        putRecord(record);
        return new IssueResult(raw, record.credentialId());
    }

    public synchronized ResolveResult resolveAndTouch(String rawToken)
            throws CredentialStoreUnavailableException {
        requireUsable(CredentialStoreUnavailableException.Operation.RESOLVE);
        String hash = TokenStore.hash(rawToken);
        RevocationAuthority.Tombstone revoked = tombstonesByHash.get(hash);
        if (revoked != null) {
            return new ResolveResult(ResolveStatus.REVOKED, null);
        }
        CredentialStore.CredentialRecord record = recordsByHash.get(hash);
        if (record == null) {
            return new ResolveResult(ResolveStatus.NOT_FOUND, null);
        }
        Instant now = Instant.now();
        if (CredentialStore.TYPE_SESSION.equals(record.type())
                && !now.isBefore(record.expiresAt())) {
            return new ResolveResult(ResolveStatus.EXPIRED, null);
        }
        if (record.revokedAt() != null) {
            // load validation normally prevents this. Keep runtime fail closed if memory is ever inconsistent.
            markUnhealthy("Snapshot says revoked but authority has no matching tombstone", null);
            throw unavailable(CredentialStoreUnavailableException.Operation.RESOLVE,
                    healthDetail, null);
        }

        CredentialStore.CredentialRecord touched = record.withLastUsedAt(now);
        List<CredentialStore.CredentialRecord> next = replace(record.credentialId(), touched);
        try {
            store.persist(domainId, next);
        } catch (IOException e) {
            markUnhealthy("Credential last-used snapshot result is uncertain", e);
            throw unavailable(CredentialStoreUnavailableException.Operation.TOUCH,
                    healthDetail, e);
        }
        putRecord(touched);
        return new ResolveResult(ResolveStatus.ACTIVE, touched);
    }

    public synchronized List<ListedCredential> list(UUID playerUuid)
            throws CredentialStoreUnavailableException {
        requireUsable(CredentialStoreUnavailableException.Operation.LIST);
        return recordsById.values().stream()
                .filter(record -> playerUuid.equals(record.playerUuid()))
                .filter(record -> CredentialStore.TYPE_LONG_LIVED.equals(record.type()))
                .filter(this::isActive)
                .sorted(Comparator.comparing(CredentialStore.CredentialRecord::issuedAt)
                        .thenComparing(CredentialStore.CredentialRecord::credentialId))
                .map(record -> new ListedCredential(record.credentialId(), record.type(), record.device(),
                        record.issuedAt(), record.lastUsedAt(), record.expiresAt()))
                .toList();
    }

    public synchronized RevokeResult revoke(UUID playerUuid, UUID credentialId)
            throws CredentialStoreUnavailableException, CredentialNotFoundException {
        requireUsable(CredentialStoreUnavailableException.Operation.REVOKE);
        CredentialStore.CredentialRecord record = recordsById.get(credentialId);
        RevocationAuthority.Tombstone existing = tombstonesById.get(credentialId);

        if (existing != null) {
            if (!playerUuid.equals(existing.playerUuid())) {
                throw new CredentialNotFoundException(credentialId);
            }
            try {
                authority.commit(existing);
            } catch (IOException e) {
                markUnhealthy("Existing tombstone could not be revalidated", e);
                throw unavailable(CredentialStoreUnavailableException.Operation.REVOKE,
                        healthDetail, e);
            }
            return new RevokeResult(credentialId, health != Health.DEGRADED);
        }
        if (record == null
                || !CredentialStore.TYPE_LONG_LIVED.equals(record.type())
                || !playerUuid.equals(record.playerUuid()) || !isActive(record)) {
            throw new CredentialNotFoundException(credentialId);
        }

        Instant revokedAt = Instant.now();
        RevocationAuthority.Tombstone tombstone = new RevocationAuthority.Tombstone(
                domainId, record.credentialId(), record.tokenHash(), record.playerUuid(), revokedAt);
        try {
            // directory fsync completion inside commit is the revoke linearization point.
            authority.commit(tombstone);
        } catch (IOException e) {
            markUnhealthy("Revocation authority commit result could not be established", e);
            throw unavailable(CredentialStoreUnavailableException.Operation.REVOKE,
                    healthDetail, e);
        }
        putTombstone(tombstone);

        CredentialStore.CredentialRecord projected = record.withRevokedAt(revokedAt);
        putRecord(projected);
        try {
            store.persist(domainId, sorted(recordsById.values()));
            return new RevokeResult(credentialId, true);
        } catch (IOException e) {
            // 線形化後なので成功を取り消さない。authority overlay は既に有効。
            health = Health.DEGRADED;
            healthDetail = "Revocation committed; snapshot projection needs reconcile: " + e.getMessage();
            LOGGER.warning(healthDetail);
            return new RevokeResult(credentialId, false);
        }
    }

    /** degraded snapshot を authority overlay から再投影する。失敗時も authority は維持する。 */
    public synchronized boolean reconcileIfNeeded() {
        if (health != Health.DEGRADED) {
            return health == Health.HEALTHY;
        }
        try {
            List<CredentialStore.CredentialRecord> reconciled = new ArrayList<>();
            for (CredentialStore.CredentialRecord record : recordsById.values()) {
                RevocationAuthority.Tombstone tombstone = tombstonesById.get(record.credentialId());
                reconciled.add(tombstone == null ? record : record.withRevokedAt(tombstone.revokedAt()));
            }
            store.persist(domainId, sorted(reconciled));
            recordsById.clear();
            recordsByHash.clear();
            for (CredentialStore.CredentialRecord record : reconciled) {
                putRecord(record);
            }
            health = Health.HEALTHY;
            healthDetail = "healthy";
            LOGGER.info("Credential snapshot reconcile completed");
            return true;
        } catch (IOException e) {
            healthDetail = "Credential snapshot reconcile still failing: " + e.getMessage();
            return false;
        }
    }

    private void loadCurrentState() throws IOException {
        clearMemory();
        boolean snapshotExists = store.exists();
        boolean manifestExists = authority.manifestExists();
        if (!snapshotExists && !manifestExists && !authority.directoryExists()) {
            health = Health.UNINITIALIZED;
            healthDetail = "Explicit credential bootstrap is required";
            return;
        }
        if (!snapshotExists || !manifestExists) {
            throw new IOException("Credential snapshot and revocation authority must both exist");
        }
        if (authority.bootstrapPendingExists()) {
            throw new IOException("Credential bootstrap transaction is incomplete");
        }

        CredentialStore.LoadedSnapshot snapshot = store.load();
        UUID authorityDomain = authority.loadManifest();
        if (!snapshot.credentialDomainId().equals(authorityDomain)) {
            throw new IOException("credential_domain_id mismatch between snapshot and authority");
        }
        domainId = authorityDomain;

        Set<UUID> tombstoneIds = new HashSet<>();
        Set<String> tombstoneHashes = new HashSet<>();
        for (RevocationAuthority.Tombstone tombstone : authority.loadTombstones(domainId)) {
            if (!tombstoneIds.add(tombstone.credentialId())
                    || !tombstoneHashes.add(tombstone.tokenHash())) {
                throw new IOException("Duplicate credential_id or token_hash in authority");
            }
            putTombstone(tombstone);
        }
        for (CredentialStore.CredentialRecord record : snapshot.records()) {
            RevocationAuthority.Tombstone byId = tombstonesById.get(record.credentialId());
            RevocationAuthority.Tombstone byHash = tombstonesByHash.get(record.tokenHash());
            if (CredentialStore.TYPE_SESSION.equals(record.type())
                    && (byId != null || byHash != null)) {
                throw new IOException("Session credential must not have a revocation tombstone");
            }
            if (byId != null && (!record.tokenHash().equals(byId.tokenHash())
                    || !record.playerUuid().equals(byId.playerUuid()))) {
                throw new IOException("Snapshot record contradicts authority tombstone");
            }
            if (byHash != null && !record.credentialId().equals(byHash.credentialId())) {
                throw new IOException("Snapshot token_hash contradicts authority tombstone");
            }
            if (record.revokedAt() != null && byId == null) {
                throw new IOException("Snapshot revoked_at has no authority tombstone");
            }
            putRecord(record);
        }
        health = Health.HEALTHY;
        healthDetail = "healthy";
    }

    private void validatePaths() throws IOException {
        java.nio.file.Path snapshot = canonicalizeProspective(store.path());
        java.nio.file.Path authorityPath = canonicalizeProspective(authority.path());
        if (snapshot.equals(authorityPath)
                || snapshot.startsWith(authorityPath)
                || authorityPath.startsWith(snapshot)) {
            throw new IOException("Credential snapshot and authority paths must be independent: "
                    + snapshot + " / " + authorityPath);
        }
        if (java.nio.file.Files.exists(snapshot) && java.nio.file.Files.exists(authorityPath)
                && java.nio.file.Files.isSameFile(snapshot, authorityPath)) {
            throw new IOException("Credential snapshot and authority resolve to the same backend identity");
        }
    }

    private void requireUsable(CredentialStoreUnavailableException.Operation operation)
            throws CredentialStoreUnavailableException {
        if (health == Health.UNINITIALIZED || health == Health.UNHEALTHY || domainId == null) {
            throw unavailable(operation, healthDetail, null);
        }
        try {
            CredentialStore.requireRegularFile(store.path(), "credential snapshot");
            UUID currentAuthorityDomain = authority.loadManifest();
            if (authority.bootstrapPendingExists() || !domainId.equals(currentAuthorityDomain)) {
                throw new IOException("Credential domain changed or has an incomplete bootstrap transaction");
            }
        } catch (IOException e) {
            markUnhealthy("Credential backend disappeared or changed while running", e);
            throw unavailable(operation, healthDetail, e);
        }
    }

    /** symlink を含む既存祖先だけ real path 化し、未作成末尾を戻して比較する。 */
    private static java.nio.file.Path canonicalizeProspective(java.nio.file.Path input)
            throws IOException {
        java.nio.file.Path absolute = input.toAbsolutePath().normalize();
        java.nio.file.Path existing = absolute;
        while (existing != null && !java.nio.file.Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("Credential path has no existing ancestor: " + input);
        }
        java.nio.file.Path real = existing.toRealPath();
        return real.resolve(existing.relativize(absolute)).normalize();
    }

    private CredentialStoreUnavailableException unavailable(
            CredentialStoreUnavailableException.Operation operation, String message, Throwable cause) {
        return cause == null
                ? new CredentialStoreUnavailableException(operation, message)
                : new CredentialStoreUnavailableException(operation, message, cause);
    }

    private int activeCount(UUID playerUuid) {
        int count = 0;
        for (CredentialStore.CredentialRecord record : recordsById.values()) {
            if (CredentialStore.TYPE_LONG_LIVED.equals(record.type())
                    && playerUuid.equals(record.playerUuid()) && isActive(record)) {
                count++;
            }
        }
        return count;
    }

    private boolean isActive(CredentialStore.CredentialRecord record) {
        return record.revokedAt() == null && !tombstonesById.containsKey(record.credentialId());
    }

    private List<CredentialStore.CredentialRecord> replace(
            UUID credentialId, CredentialStore.CredentialRecord replacement) {
        List<CredentialStore.CredentialRecord> next = new ArrayList<>(recordsById.values());
        next.removeIf(record -> credentialId.equals(record.credentialId()));
        next.add(replacement);
        return sorted(next);
    }

    private static List<CredentialStore.CredentialRecord> sorted(
            java.util.Collection<CredentialStore.CredentialRecord> records) {
        return records.stream()
                .sorted(Comparator.comparing(CredentialStore.CredentialRecord::credentialId))
                .toList();
    }

    private static String normalizeDevice(String device) {
        if (device == null) {
            return null;
        }
        String value = device.trim();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 64) {
            throw new IllegalArgumentException("device must be 1..64 characters after trim");
        }
        return value;
    }

    private void putRecord(CredentialStore.CredentialRecord record) {
        CredentialStore.CredentialRecord old = recordsById.put(record.credentialId(), record);
        if (old != null) {
            recordsByHash.remove(old.tokenHash());
        }
        recordsByHash.put(record.tokenHash(), record);
    }

    private void putTombstone(RevocationAuthority.Tombstone tombstone) {
        tombstonesById.put(tombstone.credentialId(), tombstone);
        tombstonesByHash.put(tombstone.tokenHash(), tombstone);
    }

    private void clearMemory() {
        domainId = null;
        recordsById.clear();
        recordsByHash.clear();
        tombstonesById.clear();
        tombstonesByHash.clear();
    }

    private void markUnhealthy(String message, Throwable cause) {
        clearMemory();
        health = Health.UNHEALTHY;
        healthDetail = cause == null ? message : message + ": " + cause.getMessage();
        LOGGER.severe(healthDetail);
    }

    /** auth.revoke の存在隠蔽用。 */
    public static class CredentialNotFoundException extends Exception {
        private final UUID credentialId;

        CredentialNotFoundException(UUID credentialId) {
            super("Credential not found: " + credentialId);
            this.credentialId = credentialId;
        }

        public UUID credentialId() {
            return credentialId;
        }
    }
}
