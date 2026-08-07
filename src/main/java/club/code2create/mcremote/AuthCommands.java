package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** auth pairing（pre-hello）と credential 管理（認証後）。 */
public class AuthCommands {
    private final RemoteSession session;
    private final PairingManager pairingManager;
    private final CredentialService credentialService;

    public AuthCommands(RemoteSession session, PairingManager pairingManager,
                        CredentialService credentialService) {
        this.session = session;
        this.pairingManager = pairingManager;
        this.credentialService = credentialService;
    }

    /** pre-hello で許可する pairing method。 */
    public boolean handlePreHello(ParsedCommand parsed) {
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

    /** hello 完了後の credential 管理 method。 */
    public boolean handleAuthenticated(ParsedCommand parsed) {
        return switch (parsed.getName()) {
            case "auth.listCredentials" -> {
                handleListCredentials(parsed);
                yield true;
            }
            case "auth.revoke" -> {
                handleRevoke(parsed);
                yield true;
            }
            case "auth.logout" -> {
                handleLogout(parsed);
                yield true;
            }
            default -> false;
        };
    }

    private void handlePairBegin(ParsedCommand parsed) {
        if (parsed.getParams() == null || !parsed.getParams().isJsonObject()) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        JsonObject params = parsed.getParams().getAsJsonObject();
        String tokenTypeValue = getString(params, "token_type");
        if (params.has("token_type") && tokenTypeValue == null) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        TokenStore.TokenType type;
        try {
            type = TokenStore.TokenType.fromWire(tokenTypeValue);
        } catch (IllegalArgumentException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ref", tokenTypeValue);
            session.respondError(-32602, "invalid_params", data);
            return;
        }

        String device = null;
        if (params.has("device")) {
            device = getString(params, "device");
            if (device == null || device.codePointCount(0, device.length()) < 1
                    || device.codePointCount(0, device.length()) > 64) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ref", device);
                session.respondError(-32602, "invalid_params", data);
                return;
            }
        }
        PairingManager.BeginResult result = pairingManager.begin(type, device);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pairing_id", result.pairingId());
        response.put("pair_code", result.pairCode());
        response.put("expires_in", result.expiresIn());
        session.respondResult(response);
    }

    private void handlePairPoll(ParsedCommand parsed) {
        JsonObject params = asObject(parsed.getParams());
        String pairingId = getString(params, "pairing_id");
        PairingManager.PollResult result = pairingManager.poll(pairingId);

        if (result instanceof PairingManager.Pending) {
            session.respondResult(Map.of("status", "pending"));
        } else if (result instanceof PairingManager.Ok ok) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("token", ok.token());
            session.respondResult(response);
        } else if (result instanceof PairingManager.PairExpired) {
            session.respondError(-32000, "pair_expired", null);
        } else if (result instanceof PairingManager.CredentialLimitReached limit) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "long_lived");
            data.put("limit", limit.limit());
            data.put("active", limit.active());
            session.respondError(-32000, "credential_limit_reached", data);
        } else if (result instanceof PairingManager.CredentialStoreUnavailable unavailable) {
            respondStoreUnavailable(unavailable.operation());
        } else {
            session.respondError(-32000, "pair_not_found", null);
        }
    }

    private void handleListCredentials(ParsedCommand parsed) {
        if (!isEmptyArray(parsed.getParams()) || session.getBoundUuid() == null) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            UUID current = session.getBoundCredentialId();
            for (CredentialService.ListedCredential credential
                    : credentialService.list(session.getBoundUuid())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("credential_id", credential.credentialId().toString());
                item.put("type", credential.type());
                item.put("device", credential.device());
                item.put("issued_at", credential.issuedAt().toString());
                item.put("last_used_at", credential.lastUsedAt().toString());
                item.put("expires_at", credential.expiresAt() == null
                        ? null : credential.expiresAt().toString());
                item.put("current", credential.credentialId().equals(current));
                items.add(item);
            }
            session.respondResult(Map.of("credentials", items));
        } catch (CredentialStoreUnavailableException e) {
            respondStoreUnavailable(e.operation());
        }
    }

    private void handleRevoke(ParsedCommand parsed) {
        UUID credentialId = singleCanonicalUuid(parsed.getParams());
        if (credentialId == null || session.getBoundUuid() == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (parsed.getArgs().length > 0) {
                data.put("ref", parsed.getArgs()[0]);
            }
            session.respondError(-32602, "invalid_params", data.isEmpty() ? null : data);
            return;
        }
        revokeAndClose(credentialId);
    }

    private void handleLogout(ParsedCommand parsed) {
        if (!isEmptyArray(parsed.getParams())
                || session.getBoundTokenType() != TokenStore.TokenType.LONG_LIVED
                || session.getBoundCredentialId() == null) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        revokeAndClose(session.getBoundCredentialId());
    }

    private void revokeAndClose(UUID credentialId) {
        try {
            credentialService.revoke(session.getBoundUuid(), credentialId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("credential_id", credentialId.toString());
            result.put("revoked", true);
            session.respondResult(result);
            // response は caller の queue に先に入り、その後全 session を flush-close 対象へ mark する。
            session.getPlugin().closeSessionsForCredential(credentialId);
        } catch (CredentialService.CredentialNotFoundException e) {
            session.respondError(-32602, "credential_not_found",
                    Map.of("ref", credentialId.toString()));
        } catch (CredentialStoreUnavailableException e) {
            respondStoreUnavailable(e.operation());
        }
    }

    private void respondStoreUnavailable(CredentialStoreUnavailableException.Operation operation) {
        session.respondError(-32000, "credential_store_unavailable",
                Map.of("operation", operation.wireName()));
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject()
                ? element.getAsJsonObject() : new JsonObject();
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString().trim();
    }

    private static boolean isEmptyArray(JsonElement params) {
        return params != null && params.isJsonArray() && params.getAsJsonArray().isEmpty();
    }

    private static UUID singleCanonicalUuid(JsonElement params) {
        if (params == null || !params.isJsonArray()) {
            return null;
        }
        JsonArray array = params.getAsJsonArray();
        if (array.size() != 1 || !array.get(0).isJsonPrimitive()
                || !array.get(0).getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = array.get(0).getAsString();
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
