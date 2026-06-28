package club.code2create.mcremote;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * protocol 版の正本と hello ネゴシエーションの版判定（versioning-design §8 / wire-format-design §1）。
 *
 * hello で名乗る版は **clean な protocol semver `21.0.0`**（wire-format-design §1/§6.1, DECISIONS 2026-06-27-01）。
 * 配布パッケージ版 `2100.0.0b1`（fold 形・`b1` 接尾辞）は jar/PyPI 名のレイヤであって **wire には載せない**
 * （`b1` は配布チャンネル表記で互換に無関係）。plugin / api は同一の protocol 文字列を名乗る（§2 番号対称性）。
 *
 * 本クラスは契約側（§8.2）の互換判定を一意に実装し、判定がリポ間でズレないようにする。
 */
public final class ProtocolInfo {
    private ProtocolInfo() {}

    /**
     * 本プラグインが hello で名乗る protocol semver（wire-format-design §6.2）。
     * パッケージ版は gradle.properties の pluginVersion（`2100.0.0b1`）＝こことは別レイヤ。
     */
    public static final String PROTOCOL = "21.0.0";

    // leading な major.minor.patch（万一接尾辞が付いても捨てる）
    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * クライアントが要求する protocol と本サーバの protocol を §8.2 で突き合わせる。
     *  - メジャー：一致必須（非互換）
     *  - マイナー：server.minor >= client.minor（後方互換追加をサーバが満たす）
     *  - パッチ：不問
     * パース不能（不正な版文字列）は非互換扱い。
     */
    public static boolean isCompatible(String clientProtocol) {
        int[] server = parse(PROTOCOL);
        int[] client = parse(clientProtocol);
        if (server == null || client == null) {
            return false;
        }
        return server[0] == client[0] && server[1] >= client[1];
    }

    /** 版文字列の leading major.minor.patch を返す。不正なら null。 */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.find()) {
            return null;
        }
        return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
        };
    }
}