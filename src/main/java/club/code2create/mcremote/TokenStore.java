package club.code2create.mcremote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bearer token の入口。session は in-memory、long-lived は {@link CredentialService} へ委譲する。
 * server が保持するのはどちらも SHA-256 hash のみで、生 token は発行時に一度だけ返す。
 */
public class TokenStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    public enum TokenType {
        SESSION("session", "mcrs_"),
        LONG_LIVED("long_lived", "mcrl_");

        private final String wireName;
        private final String prefix;

        TokenType(String wireName, String prefix) {
            this.wireName = wireName;
            this.prefix = prefix;
        }

        public String wireName() {
            return wireName;
        }

        public String prefix() {
            return prefix;
        }

        /** 欠落だけを既定 session とし、未知値や旧 player は受理しない。 */
        public static TokenType fromWire(String value) {
            if (value == null) {
                return SESSION;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "session" -> SESSION;
                case "long_lived" -> LONG_LIVED;
                default -> throw new IllegalArgumentException("Unknown token_type: " + value);
            };
        }
    }

    public enum ResolveStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        NOT_FOUND,
        INVALID,
        STORE_UNAVAILABLE
    }

    public record TokenRecord(UUID uuid, TokenType tokenType, Instant issuedAt,
                              Instant expiresAt, String device, Instant lastUsedAt,
                              UUID credentialId) {
        public boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    public record ResolveResult(ResolveStatus status, TokenRecord record,
                                CredentialStoreUnavailableException.Operation operation) {}

    private final ConcurrentHashMap<String, TokenRecord> sessionsByHash = new ConcurrentHashMap<>();
    private final CredentialService credentialService;

    public TokenStore(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    public String issue(UUID uuid, TokenType type, String device, long sessionTtlSeconds)
            throws CredentialStoreUnavailableException, CredentialLimitReachedException {
        if (type == TokenType.LONG_LIVED) {
            return credentialService.issue(uuid, device).token();
        }
        byte[] body = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(body);
        String raw = type.prefix() + Base64.getUrlEncoder().withoutPadding().encodeToString(body);
        Instant now = Instant.now();
        Instant expires = sessionTtlSeconds > 0 ? now.plusSeconds(sessionTtlSeconds) : null;
        sessionsByHash.put(hash(raw), new TokenRecord(
                uuid, TokenType.SESSION, now, expires, device, now, null));
        return raw;
    }

    public ResolveResult resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return new ResolveResult(ResolveStatus.INVALID, null, null);
        }
        if (rawToken.startsWith("mcrs_")) {
            return resolveSession(rawToken);
        }
        if (rawToken.startsWith("mcrl_")) {
            try {
                CredentialService.ResolveResult result = credentialService.resolveAndTouch(rawToken);
                return switch (result.status()) {
                    case ACTIVE -> {
                        CredentialStore.CredentialRecord record = result.record();
                        yield new ResolveResult(ResolveStatus.ACTIVE,
                                new TokenRecord(record.playerUuid(), TokenType.LONG_LIVED,
                                        record.issuedAt(), record.expiresAt(), record.device(),
                                        record.lastUsedAt(), record.credentialId()), null);
                    }
                    case REVOKED -> new ResolveResult(ResolveStatus.REVOKED, null, null);
                    case NOT_FOUND -> new ResolveResult(ResolveStatus.NOT_FOUND, null, null);
                };
            } catch (CredentialStoreUnavailableException e) {
                return new ResolveResult(ResolveStatus.STORE_UNAVAILABLE, null, e.operation());
            }
        }
        if (rawToken.startsWith("mcrp_")) {
            // 旧 player_token は永続 record を持たないため migration せず再ペアリングへ送る。
            return new ResolveResult(ResolveStatus.NOT_FOUND, null, null);
        }
        return new ResolveResult(ResolveStatus.INVALID, null, null);
    }

    private ResolveResult resolveSession(String rawToken) {
        String tokenHash = hash(rawToken);
        TokenRecord record = sessionsByHash.get(tokenHash);
        if (record == null) {
            return new ResolveResult(ResolveStatus.NOT_FOUND, null, null);
        }
        if (record.isExpired(Instant.now())) {
            sessionsByHash.remove(tokenHash, record);
            return new ResolveResult(ResolveStatus.EXPIRED, null, null);
        }
        return new ResolveResult(ResolveStatus.ACTIVE, record, null);
    }

    public void revokeSession(String rawToken) {
        if (rawToken != null && rawToken.startsWith("mcrs_")) {
            sessionsByHash.remove(hash(rawToken));
        }
    }

    /** SHA-256(base64url, no-pad)。 */
    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
