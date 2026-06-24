package club.code2create.mcremote;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Build state commands: setWorld(dimension) と setBuildOrigin(x, y, z)。
 * identity（誰か）と build state（どこに建てるか）を分離する設計に基づき、
 * プレイヤー（setPlayer）に依存せず world と origin を設定する。
 *
 * 座標式に暗黙の Y オフセットは持たない（絶対 y = origin_y + dy）。
 * 標高は建築コード側で意識する建付け。
 */
public class BuildStateCommands {
    private static final Logger logger = Logger.getLogger("McR_BuildState");

    // 全クライアント統一の既定原点 (200, 0, 200)
    static final int DEFAULT_ORIGIN_X = 200;
    static final int DEFAULT_ORIGIN_Y = 0;
    static final int DEFAULT_ORIGIN_Z = 200;

    private final RemoteSession session;

    public BuildStateCommands(RemoteSession session) {
        this.session = session;
    }

    /** setBuildOrigin(x, y, z) — 建築原点を設定（world は現在のもの、無ければ既定ワールド）。 */
    public void handleSetBuildOrigin(String[] args) {
        if (args.length != 3) {
            session.send("Error: setBuildOrigin requires x, y, z.");
            logger.warning("Invalid arguments for setBuildOrigin command.");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            session.send("Error: x, y, z must be integers.");
            logger.warning("Invalid coordinates for setBuildOrigin command.");
            return;
        }
        World world = currentWorldOrDefault();
        if (world == null) {
            session.send("Error: no world available for setBuildOrigin.");
            logger.warning("No world available for setBuildOrigin command.");
            return;
        }
        session.setOrigin(new Location(world, x, y, z));
        session.send("Build origin set to: " + x + ", " + y + ", " + z + " in world \"" + world.getName() + "\"");
    }

    /** setWorld(dimension) — ワールドを設定（origin の x/y/z は維持、未設定なら既定原点）。 */
    public void handleSetWorld(String[] args) {
        if (args.length != 1) {
            session.send("Error: setWorld requires a dimension.");
            logger.warning("Invalid arguments for setWorld command.");
            return;
        }
        World world = resolveWorld(args[0]);
        if (world == null) {
            session.send("Error: " + args[0] + " is an invalid dimension/world name.");
            logger.warning("Invalid dimension for setWorld command: " + args[0]);
            return;
        }
        Location origin = session.getOrigin();
        int x = origin != null ? origin.getBlockX() : DEFAULT_ORIGIN_X;
        int y = origin != null ? origin.getBlockY() : DEFAULT_ORIGIN_Y;
        int z = origin != null ? origin.getBlockZ() : DEFAULT_ORIGIN_Z;
        session.setOrigin(new Location(world, x, y, z));
        session.send("World set to \"" + world.getName() + "\" (origin: " + x + ", " + y + ", " + z + ")");
    }

    private World currentWorldOrDefault() {
        Location origin = session.getOrigin();
        if (origin != null && origin.getWorld() != null) {
            return origin.getWorld();
        }
        return defaultWorld();
    }

    private World defaultWorld() {
        World world = Bukkit.getWorld("world");
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        return world;
    }

    /**
     * dimension 文字列を解決する。overworld/nether/end を World.Environment で解決し、
     * 一致しなければ Bukkit のワールド正確名にフォールバックする（両形式を受け付ける）。
     */
    private World resolveWorld(String dimension) {
        String key = dimension.toLowerCase(Locale.ROOT).trim();
        World.Environment env = switch (key) {
            case "overworld", "world", "normal" -> World.Environment.NORMAL;
            case "nether", "the_nether" -> World.Environment.NETHER;
            case "end", "the_end" -> World.Environment.THE_END;
            default -> null;
        };
        if (env != null) {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() == env) {
                    return world;
                }
            }
        }
        return Bukkit.getWorld(dimension);
    }
}