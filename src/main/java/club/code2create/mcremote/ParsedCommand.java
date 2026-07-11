package club.code2create.mcremote;

import com.google.gson.JsonElement;

/**
 * JSON-RPC 2.0 の1メッセージをパース済みにした表現（wire-format-design §3）。
 *  - name   ＝ JSON-RPC method（ドット名、§4）
 *  - args   ＝ params 配列を位置引数として String[] に coerce（数値/真偽はそのまま文字列化）
 *  - id     ＝ クライアント採番の連番。null は notification（§3.2 応答不要）
 *  - params ＝ 生の params（配列／object）。hello のみ object（§6）なのでここから読む
 */
public class ParsedCommand {
    private final String name;
    private final String[] args;
    private final Integer id;
    private final JsonElement params;

    public ParsedCommand(String name, String[] args) {
        this(name, args, null, null);
    }

    public ParsedCommand(String name, String[] args, Integer id, JsonElement params) {
        this.name = name;
        this.args = args;
        this.id = id;
        this.params = params;
    }

    public String getName() {
        return name;
    }

    public String[] getArgs() {
        return args;
    }

    /** クライアント採番の id。null＝notification（応答を返さない）。 */
    public Integer getId() {
        return id;
    }

    public boolean isNotification() {
        return id == null;
    }

    /** 生の params（配列／object）。hello は object 形をここから読む。 */
    public JsonElement getParams() {
        return params;
    }
}