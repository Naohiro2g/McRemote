package club.code2create.mcremote;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ペアリング state machine の正本（wire-format-design §6.5・versioning-design §10.11.1 項1）。
 *
 * <p>トポロジは poll。{@code auth.pairBegin} が {@code pairing_id}＋6桁 {@code pair_code} を即返し、
 * 人間がゲーム内 {@code /mcremote pair <code>} を打つと実行者 UUID が pending に束縛され、
 * {@code auth.pairPoll} が {@code pending}→{@code ok{token}} を返す。完了 push は持たない（bN で
 * pairPoll を notification に差し替える push 形の部分集合）。
 *
 * <p><b>相関の分離</b>：{@code pair_code}（人間が打つ秘密）と {@code pairing_id}（wire 相関子）を分離。
 * poll は {@code pairing_id} 相関ゆえ pairBegin↔pairPoll の sticky 接続不要＝bridge 透明中継を保つ。
 * よって本 store は plugin レベル共有で、複数の session 入力スレッド＋{@code /mcremote pair} の
 * 主スレッドから並行アクセスされる → 全状態を {@link ConcurrentHashMap} に閉じる（CME 教訓・169e64f）。
 */
public class PairingManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_ALLOC_ATTEMPTS = 100;

    private final TokenStore tokenStore;
    private final long pairCodeTtlSeconds;
    private final long sessionTokenTtlSeconds;
    private final long playerTokenTtlSeconds;

    /** pairing_id → pending。 */
    private final ConcurrentHashMap<String, PendingPair> byPairingId = new ConcurrentHashMap<>();
    /** pair_code → pairing_id（{@code /mcremote pair} のコード照合用の索引）。 */
    private final ConcurrentHashMap<String, String> codeIndex = new ConcurrentHashMap<>();

    public PairingManager(TokenStore tokenStore, long pairCodeTtlSeconds,
                          long sessionTokenTtlSeconds, long playerTokenTtlSeconds) {
        this.tokenStore = tokenStore;
        this.pairCodeTtlSeconds = pairCodeTtlSeconds;
        this.sessionTokenTtlSeconds = sessionTokenTtlSeconds;
        this.playerTokenTtlSeconds = playerTokenTtlSeconds;
    }

    private static final class PendingPair {
        final String pairingId;
        final String pairCode;
        final TokenStore.TokenType tokenType;
        final String device;
        final Instant expiresAt;
        volatile UUID boundUuid; // null = 未束縛

        PendingPair(String pairingId, String pairCode, TokenStore.TokenType tokenType,
                    String device, Instant expiresAt) {
            this.pairingId = pairingId;
            this.pairCode = pairCode;
            this.tokenType = tokenType;
            this.device = device;
            this.expiresAt = expiresAt;
        }
    }

    // ── auth.pairBegin ─────────────────────────────────────────────── //

    public record BeginResult(String pairingId, String pairCode, long expiresIn) {}

    /** pending を新規作成し {@code pairing_id}＋6桁 {@code pair_code}＋{@code expires_in} を返す。 */
    public BeginResult begin(TokenStore.TokenType tokenType, String device) {
        Instant now = Instant.now();
        sweep(now);
        String pairingId = UUID.randomUUID().toString();
        String pairCode = reserveCode(pairingId);
        byPairingId.put(pairingId,
                new PendingPair(pairingId, pairCode, tokenType, device, now.plusSeconds(pairCodeTtlSeconds)));
        return new BeginResult(pairingId, pairCode, pairCodeTtlSeconds);
    }

    /** 6桁 code を atomically 予約（putIfAbsent で衝突回避）。 */
    private String reserveCode(String pairingId) {
        for (int i = 0; i < CODE_ALLOC_ATTEMPTS; i++) {
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (codeIndex.putIfAbsent(code, pairingId) == null) {
                return code;
            }
        }
        throw new IllegalStateException("pair_code space exhausted");
    }

    // ── /mcremote pair <code> ──────────────────────────────────────── //

    public enum BindStatus { OK, NOT_FOUND, EXPIRED, ALREADY_BOUND }

    /** {@code /mcremote pair} の実行者 UUID を pending に束縛。結果はコマンドのメッセージ用に区別する。 */
    public BindStatus bind(String pairCode, UUID uuid) {
        String pairingId = codeIndex.get(pairCode);
        if (pairingId == null) {
            return BindStatus.NOT_FOUND;
        }
        PendingPair p = byPairingId.get(pairingId);
        if (p == null) {
            return BindStatus.NOT_FOUND;
        }
        if (Instant.now().isAfter(p.expiresAt)) {
            remove(p);
            return BindStatus.EXPIRED;
        }
        synchronized (p) {
            if (p.boundUuid != null) {
                return BindStatus.ALREADY_BOUND;
            }
            p.boundUuid = uuid;
        }
        return BindStatus.OK;
    }

    // ── auth.pairPoll ──────────────────────────────────────────────── //

    public sealed interface PollResult
            permits Pending, Ok, PairExpired, PairNotFound {}

    public record Pending() implements PollResult {}

    public record Ok(String token) implements PollResult {}

    public record PairExpired() implements PollResult {}

    public record PairNotFound() implements PollResult {}

    /**
     * pending を poll。未束縛→{@code Pending}、束縛済→token を発行し pending を1回限り消費して {@code Ok}。
     * 期限切れ→{@code PairExpired}、未知 pairing_id→{@code PairNotFound}。
     */
    public PollResult poll(String pairingId) {
        if (pairingId == null || pairingId.isEmpty()) {
            return new PairNotFound();
        }
        PendingPair p = byPairingId.get(pairingId);
        if (p == null) {
            return new PairNotFound();
        }
        if (Instant.now().isAfter(p.expiresAt)) {
            remove(p);
            return new PairExpired();
        }
        UUID bound = p.boundUuid;
        if (bound == null) {
            return new Pending();
        }
        // 1回限り消費：remove の戻りで勝者を決める（同一 pairing_id の並行 poll で二重発行を避ける）。
        PendingPair consumed = byPairingId.remove(pairingId);
        if (consumed == null) {
            return new PairNotFound(); // 既に他 poll が消費済み
        }
        codeIndex.remove(consumed.pairCode, pairingId);
        long ttl = consumed.tokenType == TokenStore.TokenType.PLAYER
                ? playerTokenTtlSeconds : sessionTokenTtlSeconds;
        String token = tokenStore.issue(bound, consumed.tokenType, consumed.device, ttl);
        return new Ok(token);
    }

    // ── housekeeping ───────────────────────────────────────────────── //

    private void remove(PendingPair p) {
        byPairingId.remove(p.pairingId, p);
        codeIndex.remove(p.pairCode, p.pairingId);
    }

    /** 期限切れ pending の遅延クリーンアップ（begin 時に走らせれば足りる）。 */
    private void sweep(Instant now) {
        for (PendingPair p : byPairingId.values()) {
            if (now.isAfter(p.expiresAt)) {
                remove(p);
            }
        }
    }
}
