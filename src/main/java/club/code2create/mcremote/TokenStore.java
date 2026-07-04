package club.code2create.mcremote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 発行済み認証トークンの正本ストア（wire-format-design §6.5・versioning-design §10.11.1 項1）。
 *
 * <p>server は **hash のみ保存**（生 token は発行時にクライアントへ返す一度きり）。認可は常に
 * UUID→LuckPerms ゆえ token 本体には権限を持たせない。b2 マイルストーン1では in-memory
 * （プロセス内 {@link ConcurrentHashMap}）＝サーバ再起動で全 token 失効し、クライアントは
 * {@code auth_required} で再ペアリングする（§6.5 のクライアント契約で自動回復）。永続化は後スコープ。
 *
 * <p>{@link PairingManager} の poll と hello 検証（次ステップ）が複数の session 入力スレッドから
 * 並行アクセスするため、状態は concurrent 構造に閉じる（DECISIONS の CME 教訓・169e64f）。
 */
public class TokenStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** token 本体の乱数バイト数（256bit）。prefix と合わせて十分な entropy。 */
    private static final int TOKEN_BYTES = 32;

    /** token 種別。wire の prefix と既定値は §6.5。 */
    public enum TokenType {
        SESSION("mcrs_"),
        PLAYER("mcrp_");

        private final String prefix;

        TokenType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }

        /** wire の {@code token_type} 文字列 → enum。未知/欠落は既定 {@code session}（§6.5）。 */
        public static TokenType fromWire(String s) {
            if (s == null) {
                return SESSION;
            }
            return "player".equals(s.trim().toLowerCase()) ? PLAYER : SESSION;
        }
    }

    /** 保存レコード。生 token は保持しない（hash キーで引く）。 */
    public record TokenRecord(UUID uuid, TokenType tokenType, Instant issuedAt,
                              Instant expiresAt, String device, Instant lastUsedAt) {
        public boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, TokenRecord> byHash = new ConcurrentHashMap<>();

    /**
     * 新規 token を発行し hash を保存する。戻り値の生 token は呼び出し側が一度だけクライアントへ返す。
     *
     * @param ttlSeconds >0 で有効期限、&lt;=0 は無期限（player_token 長期・§6.5）
     */
    public String issue(UUID uuid, TokenType type, String device, long ttlSeconds) {
        byte[] body = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(body);
        String raw = type.prefix() + Base64.getUrlEncoder().withoutPadding().encodeToString(body);
        Instant now = Instant.now();
        Instant exp = ttlSeconds > 0 ? now.plusSeconds(ttlSeconds) : null;
        byHash.put(hash(raw), new TokenRecord(uuid, type, now, exp, device, now));
        return raw;
    }

    /**
     * 生 token を検証する（hello の auth 検証で使う＝次ステップ）。期限切れは lazy に除去して empty。
     */
    public Optional<TokenRecord> resolve(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            return Optional.empty();
        }
        String h = hash(rawToken);
        TokenRecord rec = byHash.get(h);
        if (rec == null) {
            return Optional.empty();
        }
        if (rec.isExpired(Instant.now())) {
            byHash.remove(h, rec);
            return Optional.empty();
        }
        return Optional.of(rec);
    }

    /** token 破棄（revoke / logout・§6.5 名前空間の後続用）。 */
    public void revoke(String rawToken) {
        if (rawToken != null && !rawToken.isEmpty()) {
            byHash.remove(hash(rawToken));
        }
    }

    /** SHA-256(base64url, no-pad)。保存キーは常にこの hash で、生 token は保持しない。 */
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
