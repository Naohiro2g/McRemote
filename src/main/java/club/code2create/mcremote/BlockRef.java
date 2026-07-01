package club.code2create.mcremote;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * block_state_ref の正準化と tolerant パース（wire-format-design §7.1）。
 *
 * 1ルール「入力 tolerate・出力 canonical-full」を namespace と state の両軸に適用する。
 *  - 入力（tolerate）：namespace 無印は `minecraft:` 補完、state は部分指定・順不同 OK（未指定 prop は default 補完）。
 *  - 出力（canonical-full）：完全修飾（バニラも `minecraft:`）＋ full state（全 prop 明示）＋ prop 名アルファベット昇順。
 *    bool は小文字・整数 state は裸の数字（Bukkit の getAsString がこの形を出すので prop ソートだけ我々が固定する）。
 */
public final class BlockRef {
    private BlockRef() {}

    /** パース失敗時の理由（§7.3 の安定 enum）と JSON-RPC code を運ぶ。 */
    public static final class BlockRefException extends Exception {
        public final int code;
        public final String reason;

        public BlockRefException(int code, String reason) {
            super(reason);
            this.code = code;
            this.reason = reason;
        }
    }

    /**
     * block_state_ref を tolerate してパースする。
     * 受理例：`stone` / `minecraft:stone` / `oak_log[axis=y]`（無印・部分 state）。
     *
     * @throws BlockRefException reason＝`malformed_ref`（括弧崩れ）／`unknown_block`（無印補完後も未知）／
     *                           `unknown_property`（そのブロックに無い prop 名, 例 `stone[axis=y]`）／
     *                           `invalid_property_value`（prop 名は有効・値が許容外, 例 `oak_log[axis=w]`）。
     *                           すべて code -32602（§7.3）。
     */
    public static BlockData parse(String ref) throws BlockRefException {
        if (ref == null) {
            throw new BlockRefException(-32602, "malformed_ref");
        }
        String r = ref.trim();
        String id = r;
        String state = "";
        int br = r.indexOf('[');
        if (br >= 0) {
            if (!r.endsWith("]")) {
                throw new BlockRefException(-32602, "malformed_ref");
            }
            id = r.substring(0, br);
            state = r.substring(br); // "[axis=y,...]" を含む
        }
        if (id.isEmpty()) {
            throw new BlockRefException(-32602, "malformed_ref");
        }

        // namespace 無印 → minecraft: 補完。未知名は失敗（黙殺しない, §7.1）。
        String namespaced = id.contains(":") ? id : "minecraft:" + id;
        Material material = Material.matchMaterial(namespaced);
        if (material == null) {
            material = Material.matchMaterial(id); // 大文字 enum 名などの保険
        }
        if (material == null || !material.isBlock()) {
            throw new BlockRefException(-32602, "unknown_block");
        }

        try {
            // state "" は全 default、"[axis=y]" は部分指定で残りを default 補完。
            return material.createBlockData(state);
        } catch (IllegalArgumentException e) {
            // §7.3: prop 名がそのブロックに無い（unknown_property, 例 stone[axis=y]）と、
            // prop 名は有効だが値が許容外（invalid_property_value, 例 oak_log[axis=w]）を切り分ける。
            if (hasUnknownProperty(material, state)) {
                throw new BlockRefException(-32602, "unknown_property");
            }
            throw new BlockRefException(-32602, "invalid_property_value");
        }
    }

    /**
     * 入力 state のキーに、そのブロックに存在しない prop 名が1つでもあれば true。
     * 有効 prop 名はブロックの default state（{@code createBlockData().getAsString()}）から取る。
     */
    private static boolean hasUnknownProperty(Material material, String state) {
        if (state.isEmpty()) {
            return false; // state 無しなら prop 起因ではない
        }
        Set<String> valid = propertyNames(material.createBlockData());
        String body = state.substring(1, state.length() - 1); // "[k=v,...]" → "k=v,..."
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            String key = (eq >= 0 ? pair.substring(0, eq) : pair).trim();
            if (!key.isEmpty() && !valid.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** BlockData の canonical 文字列から prop 名の集合を取り出す（prop 無しブロックは空集合）。 */
    private static Set<String> propertyNames(BlockData data) {
        Set<String> names = new HashSet<>();
        String s = data.getAsString();
        int br = s.indexOf('[');
        if (br < 0) {
            return names;
        }
        String body = s.substring(br + 1, s.length() - 1);
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                names.add(pair.substring(0, eq).trim());
            }
        }
        return names;
    }

    /**
     * 完全修飾・full state・prop 名アルファベット昇順の正準文字列を返す。
     * 例 `minecraft:oak_log[axis=y]`・`minecraft:grass_block[snowy=false]`。
     */
    public static String canonical(BlockData data) {
        String s = data.getAsString(); // 例 "minecraft:oak_log[axis=y]"（minecraft:付き・full state・unsorted）
        int br = s.indexOf('[');
        if (br < 0) {
            return s; // prop を持たないブロック（例 minecraft:gold_block）
        }
        String id = s.substring(0, br);
        String body = s.substring(br + 1, s.length() - 1);
        String[] props = body.split(",");
        Arrays.sort(props); // Paper の emit 順は未規定なので我々の契約で固定（往復テストを exact 文字列で書けるように）
        return id + "[" + String.join(",", props) + "]";
    }

    /** chat や warning に問題の入力をエコーするための ref（§7.3 data.ref）。 */
    public static String echo(String ref) {
        return ref == null ? "" : ref;
    }
}