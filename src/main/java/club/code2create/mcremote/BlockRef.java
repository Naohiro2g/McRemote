package club.code2create.mcremote;

import java.util.Arrays;

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
     *                           `invalid_property_value`（prop 名/値が不正）。すべて code -32602。
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
            // prop 名不在／値不正をまとめて invalid_property_value にする（細分化は §7.3 b2）。
            throw new BlockRefException(-32602, "invalid_property_value");
        }
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