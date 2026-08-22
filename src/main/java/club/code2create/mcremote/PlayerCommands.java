package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final DimensionResolver dimensions;

    // 認証（後続ベータ）で確立されるまで未設定
    private UUID playerUUID;
    private String playerName;

    public PlayerCommands(RemoteSession session, DimensionResolver dimensions) {
        this.session = session;
        this.dimensions = dimensions;
    }

    /** hello auth で検証済みの UUID を、この stream の paired player として束縛する。 */
    public void bind(UUID uuid) {
        this.playerUUID = uuid;
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        this.playerName = player.getName();
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

    /** player.getPos: paired player の現在dimensionと、stream origin相対の位置を返す。 */
    public void handleGetPos(String[] args) {
        if (args.length != 0) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        Player player = requireOnlineAuthorizedPlayer();
        if (player == null) {
            return;
        }
        session.respondResult(positionResult(player.getLocation()));
    }

    public void handleGetPosStructured(JsonElement params) {
        try {
            WireParams.positional(params, 0);
            handleGetPos(new String[0]);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    public void handleSetPosStructured(JsonElement params) {
        final JsonArray args;
        try {
            args = WireParams.positional(params, 4);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        Player player = requireOnlineAuthorizedPlayer();
        if (player == null) {
            return;
        }
        try {
            String dimensionRef = WireParams.string(args, 0);
            DimensionResolver.ResolvedDimension resolved = dimensions.resolve(dimensionRef);
            if (!resolved.isLoaded()) {
                session.respondError(-32000, "unknown_dimension",
                        BuildStateCommands.dimensionData(resolved.canonicalKey()));
                return;
            }
            Location origin = session.getOrigin();
            if (origin == null) {
                session.respondError(-32000, "origin_not_set", null);
                return;
            }
            double x = origin.getX() + WireParams.finiteDouble(args, 1);
            double y = origin.getY() + WireParams.finiteDouble(args, 2);
            double z = origin.getZ() + WireParams.finiteDouble(args, 3);
            if (!areFinite(x, y, z)) {
                throw new IllegalArgumentException("absolute coordinates must be finite");
            }
            Location current = player.getLocation();
            Location target = new Location(resolved.world(), x, y, z, current.getYaw(), current.getPitch());
            if (!player.teleport(target)) {
                session.respondError(-32000, "teleport_failed", null);
                return;
            }
            session.respondResult(positionResult(player.getLocation()));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    /** player.getPose: paired player の現在dimension・相対位置・yaw/pitchを返す。 */
    public void handleGetPose(String[] args) {
        if (args.length != 0) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        Player player = requireOnlineAuthorizedPlayer();
        if (player == null) {
            return;
        }
        session.respondResult(poseResult(player.getLocation()));
    }

    public void handleGetPoseStructured(JsonElement params) {
        try {
            WireParams.positional(params, 0);
            handleGetPose(new String[0]);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    public void handleSetPoseStructured(JsonElement params) {
        final JsonArray args;
        try {
            args = WireParams.positional(params, 6);
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
            return;
        }
        Player player = requireOnlineAuthorizedPlayer();
        if (player == null) {
            return;
        }
        try {
            String dimensionRef = WireParams.string(args, 0);
            DimensionResolver.ResolvedDimension resolved = dimensions.resolve(dimensionRef);
            if (!resolved.isLoaded()) {
                session.respondError(-32000, "unknown_dimension",
                        BuildStateCommands.dimensionData(resolved.canonicalKey()));
                return;
            }
            Location origin = session.getOrigin();
            if (origin == null) {
                session.respondError(-32000, "origin_not_set", null);
                return;
            }
            double x = origin.getX() + WireParams.finiteDouble(args, 1);
            double y = origin.getY() + WireParams.finiteDouble(args, 2);
            double z = origin.getZ() + WireParams.finiteDouble(args, 3);
            double yaw = WireParams.finiteDouble(args, 4);
            double pitch = WireParams.finiteDouble(args, 5);
            WireNumbers.requirePitch(pitch);
            if (!areFinite(x, y, z)) {
                throw new IllegalArgumentException("absolute coordinates must be finite");
            }
            // Paper stores angles as float. Periodic normalization prevents finite double input
            // from overflowing that native type; wire display rounding is not applied here.
            float nativeYaw = (float) WireNumbers.normalizeYaw(yaw);
            Location target = new Location(resolved.world(), x, y, z, nativeYaw, (float) pitch);
            if (!player.teleport(target)) {
                session.respondError(-32000, "teleport_failed", null);
                return;
            }
            session.respondResult(poseResult(player.getLocation()));
        } catch (IllegalArgumentException e) {
            session.respondError(-32602, "invalid_params", null);
        }
    }

    static PoseInput parsePoseInput(String[] args) {
        if (args.length != 6) {
            throw new IllegalArgumentException("player.setPose requires 6 parameters");
        }
        double relativeX = Double.parseDouble(args[1]);
        double relativeY = Double.parseDouble(args[2]);
        double relativeZ = Double.parseDouble(args[3]);
        double yaw = Double.parseDouble(args[4]);
        double pitch = Double.parseDouble(args[5]);
        if (!areFinite(relativeX, relativeY, relativeZ, yaw, pitch)
                || pitch < -90.0 || pitch > 90.0) {
            throw new IllegalArgumentException("pose values must be finite and pitch must be within -90..90");
        }
        return new PoseInput(relativeX, relativeY, relativeZ, normalizeYaw(yaw), (float) pitch);
    }

    static float normalizeYaw(double yaw) {
        return (float) WireNumbers.normalizeYaw(yaw);
    }

    static boolean areFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    record PoseInput(double relativeX, double relativeY, double relativeZ, float yaw, float pitch) {
    }

    private Player requireOnlineAuthorizedPlayer() {
        if (playerUUID == null) {
            session.respondError(-32000, "auth_required", null);
            return null;
        }
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null || !player.isOnline()) {
            session.respondError(-32000, "player_offline", null);
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerUUID);
        if (!session.getPlugin().getPermissionManager().canConstructOnline(offline)) {
            session.respondError(-32000, "permission_denied", null);
            return null;
        }
        return player;
    }

    private Map<String, Object> positionResult(Location loc) {
        return positionResult(loc, session.getOrigin());
    }

    static Map<String, Object> positionResult(Location loc, Location origin) {
        double x = loc.getX() - origin.getX();
        double y = loc.getY() - origin.getY();
        double z = loc.getZ() - origin.getZ();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", DimensionResolver.canonical(loc.getWorld()));
        result.put("pos", List.of(
                WireNumbers.position(x),
                WireNumbers.position(y),
                WireNumbers.position(z)));
        return result;
    }

    private Map<String, Object> poseResult(Location loc) {
        return poseResult(loc, session.getOrigin());
    }

    static Map<String, Object> poseResult(Location loc, Location origin) {
        Map<String, Object> result = positionResult(loc, origin);
        result.put("yaw", WireNumbers.yaw(loc.getYaw()));
        result.put("pitch", WireNumbers.pitch(loc.getPitch()));
        return result;
    }

}
