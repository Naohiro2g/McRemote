package club.code2create.mcremote;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 認証名前空間 {@code auth.*} の pre-hello ハンドラ（wire-format-design §6.5）。
 *
 * <p>ペアリングは hello の**前段**の独立メソッドゆえ、{@link RemoteSession} の hello 門番より前に
 * {@link #handle} を通す。対応した場合は true（門番を通さない・helloComplete は立てない・close しない）、
 * 未対応 method なら false を返して呼び出し側の門番に委ねる。
 *
 * <p>params は object 形（hello と同じく {@link ParsedCommand#getParams()} の raw {@link JsonElement}）。
 * 応答は {@link RemoteSession#respondResult}/{@link RemoteSession#respondError} をそのまま流用。
 * pairing 固有の失敗 reason は {@code pair_expired}/{@code pair_not_found}（-32000 番台＋data.reason・§6.3/§7.3）。
 */
public class AuthCommands {
    private final RemoteSession session;
    private final PairingManager pairingManager;

    public AuthCommands(RemoteSession session, PairingManager pairingManager) {
        this.session = session;
        this.pairingManager = pairingManager;
    }

    /** {@code auth.*} を処理したら true。未対応 method は false（呼び出し側が門番を継続）。 */
    public boolean handle(ParsedCommand parsed) {
        return switch (parsed.getName()) {
            case "auth.pairBegin" -> {
                handlePairBegin(parsed);
                yield true;
            }
            case "auth.pairPoll" -> {
                handlePairPoll(parsed);
                yield true;
            }
            default -> false;
        };
    }

    private void handlePairBegin(ParsedCommand parsed) {
        JsonObject params = asObject(parsed.getParams());
        TokenStore.TokenType type = TokenStore.TokenType.fromWire(getString(params, "token_type"));
        String device = getString(params, "device");
        PairingManager.BeginResult r = pairingManager.begin(type, device);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pairing_id", r.pairingId());
        result.put("pair_code", r.pairCode());
        result.put("expires_in", r.expiresIn());
        session.respondResult(result);
    }

    private void handlePairPoll(ParsedCommand parsed) {
        JsonObject params = asObject(parsed.getParams());
        String pairingId = getString(params, "pairing_id");
        PairingManager.PollResult res = pairingManager.poll(pairingId);

        if (res instanceof PairingManager.Pending) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "pending"); // pending は error でなく result.status（§6.5）
            session.respondResult(result);
        } else if (res instanceof PairingManager.Ok ok) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("token", ok.token()); // 単一ソース原則：ok は token だけ返す（§6.5）
            session.respondResult(result);
        } else if (res instanceof PairingManager.PairExpired) {
            session.respondError(-32000, "pair_expired", null);
        } else { // PairNotFound（未知/欠落 pairing_id）
            session.respondError(-32000, "pair_not_found", null);
        }
    }

    private static JsonObject asObject(JsonElement e) {
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
    }

    private static String getString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString().trim() : null;
    }
}
