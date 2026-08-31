package club.code2create.mcremote.liveprobe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Non-production, filesystem-controlled live observation probe. */
public final class B7LiveProbe extends JavaPlugin implements Listener {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Set<String> ACTIONS = Set.of("arm", "snapshot", "remove", "teleport", "cleanup");

    private Path controlPath;
    private Path observationPath;
    private long appliedSequence = Long.MIN_VALUE;
    private ActiveArm arm;
    private Properties observation = new Properties();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        controlPath = getDataFolder().toPath().resolve("control.properties");
        observationPath = getDataFolder().toPath().resolve("observation.properties");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::pollControl, 1L, 1L);
        getLogger().info("b7 live probe enabled; non-production filesystem control only");
    }

    private void pollControl() {
        if (!Files.isRegularFile(controlPath)) {
            return;
        }
        try (InputStream input = Files.newInputStream(controlPath)) {
            Properties control = new Properties();
            control.load(input);
            long sequence = Long.parseLong(required(control, "sequence"));
            if (sequence == appliedSequence) {
                return;
            }
            appliedSequence = sequence;
            apply(control, sequence);
        } catch (Exception error) {
            failObservation(error.getClass().getSimpleName() + ": " + safeMessage(error));
        }
    }

    private void apply(Properties control, long sequence) throws IOException {
        String runId = required(control, "run_id");
        String action = required(control, "action").toLowerCase(Locale.ROOT);
        if (!RUN_ID.matcher(runId).matches() || !ACTIONS.contains(action)) {
            throw new IllegalArgumentException("invalid run_id or action");
        }
        World world = world(required(control, "world"));
        Properties next = base(sequence, runId, action);
        switch (action) {
            case "arm" -> arm(control, world, next);
            case "snapshot" -> snapshot(control, world, next);
            case "remove" -> mutateNearest(control, world, next, false);
            case "teleport" -> mutateNearest(control, world, next, true);
            case "cleanup" -> cleanup(control, world, next);
            default -> throw new IllegalArgumentException("unsupported action");
        }
    }

    private void arm(Properties control, World world, Properties next) throws IOException {
        Location target = location(control, world, "target");
        boolean cancel = Boolean.parseBoolean(required(control, "cancel"));
        int laterTicks = integer(control, "later_ticks", 1, 1200);
        List<Point> blockPoints = points(control.getProperty("block_points", ""), world);
        List<Point> entityPoints = points(control.getProperty("entity_points", ""), world);
        arm = new ActiveArm(next.getProperty("run_id"), world.getKey().toString(), target,
                cancel, laterTicks, blockPoints, entityPoints, 0);
        observe(next, "baseline", world, blockPoints, entityPoints);
        next.setProperty("status", "ready");
        next.setProperty("cancel.requested", Boolean.toString(cancel));
        write(next);
        observation = next;
    }

    private void snapshot(Properties control, World world, Properties next) throws IOException {
        observe(next, "snapshot", world,
                points(control.getProperty("block_points", ""), world),
                points(control.getProperty("entity_points", ""), world));
        next.setProperty("status", "complete");
        write(next);
    }

    private void mutateNearest(Properties control, World world, Properties next, boolean teleport)
            throws IOException {
        Location source = location(control, world, "entity");
        Entity entity = nearestNonPlayer(world, source, decimal(control, "entity_radius", 0.75));
        if (entity == null) {
            throw new IllegalStateException("no non-player entity at requested observation point");
        }
        next.setProperty("entity.before.type", entity.getType().getKey().toString());
        if (teleport) {
            World destinationWorld = world(required(control, "destination_world"));
            Location destination = location(control, destinationWorld, "destination");
            next.setProperty("mutation.success", Boolean.toString(entity.teleport(destination)));
        } else {
            entity.remove();
            next.setProperty("mutation.success", "true");
        }
        next.setProperty("status", "complete");
        write(next);
    }

    private void cleanup(Properties control, World world, Properties next) throws IOException {
        Location min = location(control, world, "min");
        Location max = location(control, world, "max");
        int removed = 0;
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            Location location = entity.getLocation();
            if (!(entity instanceof Player) && inside(location, min, max)) {
                entity.remove();
                removed++;
            }
        }
        next.setProperty("cleanup.entities_removed", Integer.toString(removed));
        next.setProperty("status", "complete");
        write(next);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLightningStart(LightningStrikeEvent event) {
        ActiveArm active = arm;
        if (active == null || !active.worldKey().equals(event.getLightning().getWorld().getKey().toString())) {
            return;
        }
        // Weather lightning is unrelated background activity. The product call is expected to
        // surface as CUSTOM; another CUSTOM event in this isolated region remains a test failure.
        if (event.getCause() != LightningStrikeEvent.Cause.CUSTOM) {
            return;
        }
        Location actual = event.getLightning().getLocation();
        boolean exact = sameCoordinates(active.target(), actual);
        int count = active.eventCount() + (exact ? 1 : 0);
        arm = active.withEventCount(count);
        observation.setProperty("event.cause", event.getCause().name());
        observation.setProperty("event.x", Double.toString(actual.getX()));
        observation.setProperty("event.y", Double.toString(actual.getY()));
        observation.setProperty("event.z", Double.toString(actual.getZ()));
        observation.setProperty("event.exact_target", Boolean.toString(exact));
        observation.setProperty("event.exact_count", Integer.toString(count));
        observation.setProperty("event.cancelled.before_probe", Boolean.toString(event.isCancelled()));
        if (active.cancel()) {
            event.setCancelled(true);
        }
        observe(observation, "tick0", event.getLightning().getWorld(), active.blockPoints(), active.entityPoints());
        try {
            observation.setProperty("status", "event_seen");
            write(observation);
        } catch (IOException error) {
            getLogger().warning("failed to write event observation: " + safeMessage(error));
        }
        Bukkit.getScheduler().runTaskLater(this, () -> finishLater(active.runId()), active.laterTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLightningFinal(LightningStrikeEvent event) {
        ActiveArm active = arm;
        if (active == null || !active.worldKey().equals(event.getLightning().getWorld().getKey().toString())) {
            return;
        }
        if (event.getCause() != LightningStrikeEvent.Cause.CUSTOM) {
            return;
        }
        observation.setProperty("event.cancelled.final", Boolean.toString(event.isCancelled()));
        try {
            write(observation);
        } catch (IOException error) {
            getLogger().warning("failed to write final cancellation observation: " + safeMessage(error));
        }
    }

    private void finishLater(String runId) {
        ActiveArm active = arm;
        if (active == null || !active.runId().equals(runId)) {
            return;
        }
        observe(observation, "later", active.target().getWorld(), active.blockPoints(), active.entityPoints());
        observation.setProperty("status", "complete");
        try {
            write(observation);
        } catch (IOException error) {
            getLogger().warning("failed to write later-tick observation: " + safeMessage(error));
        }
    }

    private void observe(Properties target, String prefix, World world,
                         List<Point> blocks, List<Point> entities) {
        for (Point point : blocks) {
            Block block = world.getBlockAt(point.location());
            target.setProperty(prefix + ".block." + point.label(), block.getType().getKey().toString());
        }
        for (Point point : entities) {
            Entity entity = nearestNonPlayer(world, point.location(), 1.5);
            String base = prefix + ".entity." + point.label();
            if (entity == null) {
                target.setProperty(base + ".present", "false");
            } else {
                target.setProperty(base + ".present", "true");
                target.setProperty(base + ".type", entity.getType().getKey().toString());
                Location location = entity.getLocation();
                target.setProperty(base + ".x", Double.toString(location.getX()));
                target.setProperty(base + ".y", Double.toString(location.getY()));
                target.setProperty(base + ".z", Double.toString(location.getZ()));
                if (entity instanceof LivingEntity living) {
                    target.setProperty(base + ".health", Double.toString(living.getHealth()));
                }
            }
        }
        long players = world.getPlayers().stream().filter(Player::isOnline).count();
        target.setProperty(prefix + ".online_players_in_world", Long.toString(players));
    }

    private Entity nearestNonPlayer(World world, Location point, double radius) {
        return world.getNearbyEntities(point, radius, radius, radius, entity -> !(entity instanceof Player))
                .stream().min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(point)))
                .orElse(null);
    }

    private List<Point> points(String encoded, World world) {
        List<Point> result = new ArrayList<>();
        if (encoded.isBlank()) {
            return result;
        }
        for (String item : encoded.split(";")) {
            String[] fields = item.split(":", -1);
            if (fields.length != 4 || !fields[0].matches("[A-Za-z0-9_-]{1,40}")) {
                throw new IllegalArgumentException("invalid observation point");
            }
            result.add(new Point(fields[0], new Location(world,
                    Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]))));
        }
        return result;
    }

    private World world(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        World world = namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
        if (world == null) {
            throw new IllegalArgumentException("requested world is not loaded");
        }
        return world;
    }

    private Location location(Properties properties, World world, String prefix) {
        return new Location(world,
                Double.parseDouble(required(properties, prefix + ".x")),
                Double.parseDouble(required(properties, prefix + ".y")),
                Double.parseDouble(required(properties, prefix + ".z")));
    }

    private static boolean sameCoordinates(Location left, Location right) {
        return Double.doubleToLongBits(left.getX()) == Double.doubleToLongBits(right.getX())
                && Double.doubleToLongBits(left.getY()) == Double.doubleToLongBits(right.getY())
                && Double.doubleToLongBits(left.getZ()) == Double.doubleToLongBits(right.getZ());
    }

    private static boolean inside(Location value, Location a, Location b) {
        return value.getX() >= Math.min(a.getX(), b.getX()) && value.getX() <= Math.max(a.getX(), b.getX())
                && value.getY() >= Math.min(a.getY(), b.getY()) && value.getY() <= Math.max(a.getY(), b.getY())
                && value.getZ() >= Math.min(a.getZ(), b.getZ()) && value.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    private static int integer(Properties properties, String key, int min, int max) {
        int value = Integer.parseInt(required(properties, key));
        if (value < min || value > max) {
            throw new IllegalArgumentException("integer outside probe bounds");
        }
        return value;
    }

    private static double decimal(Properties properties, String key, double fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private Properties base(long sequence, String runId, String action) {
        Properties result = new Properties();
        result.setProperty("schema", "1");
        result.setProperty("sequence", Long.toString(sequence));
        result.setProperty("run_id", runId);
        result.setProperty("action", action);
        return result;
    }

    private void failObservation(String message) {
        Properties failed = new Properties();
        failed.setProperty("schema", "1");
        failed.setProperty("sequence", Long.toString(appliedSequence));
        failed.setProperty("status", "error");
        failed.setProperty("error", message.replace('\n', ' '));
        try {
            write(failed);
        } catch (IOException ignored) {
            getLogger().warning("failed to write probe error observation");
        }
    }

    private void write(Properties properties) throws IOException {
        Path temporary = observationPath.resolveSibling("observation.properties.tmp");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            sorted.put(name, properties.getProperty(name));
        }
        Properties stable = new Properties();
        stable.putAll(sorted);
        try (OutputStream output = Files.newOutputStream(temporary)) {
            stable.store(output, "McRemote b7 live probe; contains no credential or UUID fields");
        }
        try {
            Files.move(temporary, observationPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, observationPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? "no detail" : error.getMessage();
    }

    private record Point(String label, Location location) { }

    private record ActiveArm(String runId, String worldKey, Location target, boolean cancel,
                             int laterTicks, List<Point> blockPoints, List<Point> entityPoints,
                             int eventCount) {
        ActiveArm withEventCount(int value) {
            return new ActiveArm(runId, worldKey, target, cancel, laterTicks,
                    blockPoints, entityPoints, value);
        }
    }
}
