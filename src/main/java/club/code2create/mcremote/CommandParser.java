package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * 直 TCP の1行＝1 JSON（compact, \n 終端）を JSON-RPC 2.0 としてパースする（wire-format-design §2/§3）。
 * method/params/id を取り出し、params 配列は位置引数 String[] に coerce する
 * （hello は object 形なので {@link ParsedCommand#getParams()} 側から読む）。
 */
public class CommandParser {
    public ParsedCommand parse(String line) {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("Not a JSON-RPC object: " + line);
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Malformed JSON: " + line);
        }

        if (!"2.0".equals(asStringOrNull(root.get("jsonrpc")))) {
            throw new IllegalArgumentException("Missing/invalid jsonrpc version: " + line);
        }
        JsonElement methodEl = root.get("method");
        if (methodEl == null || !methodEl.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing method: " + line);
        }
        String method = methodEl.getAsString();

        Integer id = null;
        JsonElement idEl = root.get("id");
        if (idEl != null && !idEl.isJsonNull()) {
            id = idEl.getAsInt();
        }

        JsonElement params = root.get("params");
        return new ParsedCommand(method, toPositional(params), id, params);
    }

    /** params 配列 → String[]（数値/真偽は素の文字列化）。object／欠如は空配列。 */
    private String[] toPositional(JsonElement params) {
        if (params == null || !params.isJsonArray()) {
            return new String[0];
        }
        JsonArray arr = params.getAsJsonArray();
        String[] out = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (e.isJsonNull()) {
                out[i] = "";
            } else if (e.isJsonPrimitive()) {
                out[i] = e.getAsString();
            } else {
                out[i] = e.toString();
            }
        }
        return out;
    }

    private String asStringOrNull(JsonElement e) {
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }
}