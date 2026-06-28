package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.UUID;

/**
 * セッションに紐づく identity（誰が建てているか）の保持。
 *
 * b1（protocol 21.0.0）では identity を build state（setWorld/setBuildOrigin）から分離し、
 * 旧 setPlayer 経路は撤去した。identity の確立は後続ベータの認証（pair/hello/LuckPerms）で行う。
 * それまで playerUUID/playerName は未設定（null）＝attached player 無し。
 *
 * 互換: build.range は attached player があればその権限から、無ければ config の
 * default_build_range にフォールバックする（{@link BlockEditCommands} 参照）。
 */
public class PlayerCommands {
    private final RemoteSession session;

    // 認証（後続ベータ）で確立されるまで未設定
    private UUID playerUUID;
    private String playerName;

    public PlayerCommands(RemoteSession session) {
        this.session = session;
    }

    /**
     * オフラインプレイヤーも含め、セッションに紐付けられたプレイヤーを返す。
     * identity 未確立（b1）では null。
     */
    public OfflinePlayer getAttachedPlayer() {
        if (playerUUID == null) {
            return null;
        }
        return Bukkit.getOfflinePlayer(playerUUID);
    }

    /** セッションに紐付いたプレイヤー名（identity 未確立なら null）。 */
    public String getPlayerName() {
        return playerName;
    }
}
