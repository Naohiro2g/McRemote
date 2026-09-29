package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Direct consumer of the Scratch-owned protocol 23.2 entity/particle fixture
 * （scratch-editor {@code 0735a9c957d069f719bee9c91e8be0f9322f4920}）。Every case is mapped to the
 * production handler or registry path.
 */
class EntityParticleFixtureContractTest {
    private static final String FIXTURE = "/fixtures/entity-particle-v23.2.json";
    private static final String OWNER_SHA256 =
            "09c1565bf81d33c92d6282e6e20d926559168cb9d780c07d30ad2f9f5895640e";
    private static final World OVERWORLD = world("overworld");
    private static final World NETHER = world("the_nether");

    @Test
    void exactOwnerBytesAndCaseCounts() throws Exception {
        byte[] bytes = fixtureBytes();
        assertEquals(OWNER_SHA256, sha256(bytes));
        JsonObject root = root();
        assertEquals("mcremote.entity-particle.v23.2", root.get("schema").getAsString());
        assertEquals(ProtocolInfo.PROTOCOL, root.get("protocol").getAsString());
        assertEquals(19, root.getAsJsonObject("nearby").getAsJsonArray("cases").size());
        assertEquals(7, root.getAsJsonObject("nearby").getAsJsonArray("handle_transaction_cases").size());
        assertEquals(7, root.getAsJsonObject("entity_lifecycle").getAsJsonArray("cases").size());
        assertEquals(26, root.getAsJsonObject("particle_stage_2").getAsJsonArray("cases").size());
    }

    @Test
    void distributionPolicyMatchesRuntimeDefaults() throws Exception {
        JsonObject nearby = root().getAsJsonObject("nearby");
        JsonObject caps = nearby.getAsJsonObject("protocol_caps");
        JsonObject policy = nearby.getAsJsonObject("distribution_policy");
        RuntimePolicy defaults = RuntimePolicy.from(new YamlConfiguration());
        assertEquals(caps.get("radius_max").getAsInt(), RuntimePolicy.PROTOCOL_MAX_NEARBY_RADIUS);
        assertEquals(caps.get("max_entities_max").getAsInt(), RuntimePolicy.PROTOCOL_MAX_NEARBY_ENTITIES);
        assertEquals(policy.get("max_radius").getAsInt(), defaults.nearbyMaxRadius());
        assertEquals(policy.get("max_entities").getAsInt(), defaults.nearbyMaxEntities());
        YamlConfiguration raised = new YamlConfiguration();
        raised.set("entities.nearby_max_radius", 100);
        raised.set("entities.nearby_max_entities", 100);
        assertEquals(64, RuntimePolicy.from(raised).nearbyMaxRadius(), "may_raise is false");
        assertEquals(64, RuntimePolicy.from(raised).nearbyMaxEntities(), "may_raise is false");
        JsonObject particle = root().getAsJsonObject("particle_stage_2");
        assertEquals(particle.get("distribution_max_count").getAsInt(), defaults.maxParticleCount());
    }

    // ---- nearby ----

    @Test
    void nearbyCases() throws Exception {
        for (JsonElement element : root().getAsJsonObject("nearby").getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            String id = c.get("id").getAsString();
            if (c.has("calculation_only")) {
                JsonArray spans = c.getAsJsonArray("chunk_spans_decimal");
                assertThrows(ArithmeticException.class, () -> EntityLifecycleCommands.workCost(
                        Long.parseLong(spans.get(0).getAsString()),
                        Long.parseLong(spans.get(1).getAsString()), 1), id);
                assertEquals("work_limit_exceeded", c.get("reason").getAsString(), id);
                continue;
            }
            NearbyHarness h = new NearbyHarness(c);
            h.commands.handleGetNearbyEntities(c.get("params"));

            if (c.has("reason")) {
                assertEquals(c.get("reason").getAsString(), h.context.reason, id);
            } else {
                assertNull(h.context.reason, id);
            }
            if (c.has("candidate_search_calls")) {
                assertEquals(c.get("candidate_search_calls").getAsInt(), h.searches.get(), id);
            }
            if (c.has("handle_issuance_calls") || c.has("handle_resolution_calls")) {
                assertEquals(0, h.handles.size(), id);
            }
            if (c.has("work_cost") && !c.has("reason")) {
                assertEquals(c.get("work_cost").getAsLong(), h.context.lastSetterUnits, id);
            }
            if (c.has("chunk_bounds")) {
                JsonArray params = c.getAsJsonArray("params");
                Location origin = h.context.getOrigin();
                double x = origin.getX() + params.get(0).getAsDouble();
                double z = origin.getZ() + params.get(2).getAsDouble();
                double r = params.get(3).getAsDouble();
                JsonArray bounds = c.getAsJsonArray("chunk_bounds");
                assertEquals(List.of(bounds.get(0).getAsLong(), bounds.get(1).getAsLong(),
                                bounds.get(2).getAsLong(), bounds.get(3).getAsLong()),
                        List.of(EntityLifecycleCommands.chunkIndex(x - r), EntityLifecycleCommands.chunkIndex(x + r),
                                EntityLifecycleCommands.chunkIndex(z - r), EntityLifecycleCommands.chunkIndex(z + r)),
                        id);
            }
            if (c.has("selected_uuids")) {
                List<String> expected = new ArrayList<>();
                c.getAsJsonArray("selected_uuids").forEach(uuid -> expected.add(uuid.getAsString()));
                assertEquals(expected, h.resultUuids(), id);
            }
            if (c.has("result")) {
                JsonArray expected = c.getAsJsonArray("result");
                List<?> actual = (List<?>) h.context.result;
                assertEquals(expected.size(), actual.size(), id);
                for (int i = 0; i < expected.size(); i++) {
                    JsonObject want = expected.get(i).getAsJsonObject();
                    Map<?, ?> got = (Map<?, ?>) actual.get(i);
                    assertEquals(List.of("handle", "type", "pos"), new ArrayList<>(got.keySet()), id);
                    assertTrue(((String) got.get("handle")).startsWith(EntityHandleRegistry.PREFIX), id);
                    assertEquals(want.get("type").getAsString(), got.get("type"), id);
                    assertEquals(doubles(want.getAsJsonArray("pos")), doubles(got.get("pos")), id);
                }
            }
        }
    }

    @Test
    void handleTransactionCases() throws Exception {
        for (JsonElement element : root().getAsJsonObject("nearby").getAsJsonArray("handle_transaction_cases")) {
            JsonObject c = element.getAsJsonObject();
            String id = c.get("id").getAsString();
            switch (id) {
                case "B8-H01" -> {
                    Registry r = new Registry(4);
                    FakeEntity e = r.entity(OVERWORLD);
                    String old = r.handles.issue(e.entity);
                    List<EntityHandleRegistry.Issued> issued = r.handles.issueAll(List.of(e.entity));
                    assertEquals(old, issued.get(0).handle(), id);
                    assertEquals(1, r.handles.size(), id);
                }
                case "B8-H02" -> {
                    Registry r = new Registry(4);
                    FakeEntity e = r.entity(OVERWORLD);
                    String old = r.handles.issue(e.entity);
                    e.location.setWorld(NETHER);
                    String fresh = r.handles.issueAll(List.of(e.entity)).get(0).handle();
                    assertFalse(old.equals(fresh), id);
                    assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND, r.handles.resolve(old).status(), id);
                    assertEquals(c.get("old_handle_after_success").getAsString(), "entity_not_found", id);
                }
                case "B8-H03", "B8-H04" -> {
                    boolean fits = EntityHandleRegistry.fitsCapacity(
                            c.get("current_handles").getAsInt(), c.get("staged_revokes").getAsInt(),
                            c.get("new_handles").getAsInt(), c.get("open_spawn_reservations").getAsInt(),
                            c.get("capacity").getAsInt());
                    assertEquals(c.has("accepted") && c.get("accepted").getAsBoolean(), fits, id);
                    if (id.equals("B8-H04")) {
                        // Reachable equivalent: a full registry plus an open reservation rejects the
                        // revoke+reissue and keeps the old handle.
                        Registry r = new Registry(2);
                        FakeEntity moved = r.entity(OVERWORLD);
                        String old = r.handles.issue(moved.entity);
                        EntityHandleRegistry.Reservation open = r.handles.reserve();
                        moved.location.setWorld(NETHER);
                        FakeEntity extra = r.entity(NETHER);
                        assertThrows(EntityHandleRegistry.CapacityException.class,
                                () -> r.handles.issueAll(List.of(moved.entity, extra.entity)), id);
                        assertEquals(1, r.handles.size(), id);
                        moved.location.setWorld(OVERWORLD);
                        assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE, r.handles.resolve(old).status(), id);
                        open.close();
                    }
                }
                case "B8-H05" -> {
                    Registry r = new Registry(4);
                    FakeEntity first = r.entity(OVERWORLD);
                    FakeEntity second = r.entity(OVERWORLD);
                    String secondHandle = r.handles.issue(second.entity);
                    second.dead.set(true);
                    List<EntityHandleRegistry.Issued> issued = r.handles.issueAll(List.of(first.entity, second.entity));
                    assertEquals(1, issued.size(), id);
                    assertEquals(first.entity, issued.get(0).entity(), id);
                    assertEquals(EntityHandleRegistry.ResolveStatus.NOT_FOUND,
                            r.handles.resolve(secondHandle).status(), id);
                }
                case "B8-H06" -> {
                    Registry r = new Registry(4);
                    FakeEntity gone = r.entity(OVERWORLD);
                    gone.dead.set(true);
                    assertEquals(List.of(), r.handles.issueAll(List.of(gone.entity)), id);
                }
                case "B8-H07" -> {
                    Registry r = new Registry(2);
                    FakeEntity gone = r.entity(OVERWORLD);
                    r.handles.issue(gone.entity);
                    gone.dead.set(true);
                    List<Entity> fresh = List.of(r.entity(OVERWORLD).entity, r.entity(OVERWORLD).entity,
                            r.entity(OVERWORLD).entity);
                    List<Entity> candidates = new ArrayList<>();
                    candidates.add(gone.entity);
                    candidates.addAll(fresh);
                    assertThrows(EntityHandleRegistry.CapacityException.class,
                            () -> r.handles.issueAll(candidates), id);
                    assertEquals(1, r.handles.size(), id + ": staged revoke rolled back");
                }
                default -> fail("unmapped case " + id);
            }
        }
    }

    // ---- entity lifecycle ----

    @Test
    void entityLifecycleCases() throws Exception {
        for (JsonElement element : root().getAsJsonObject("entity_lifecycle").getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            String id = c.get("id").getAsString();
            NearbyHarness h = new NearbyHarness(new JsonObject());
            FakeEntity entity = h.registry.entity(OVERWORLD);
            entity.location.set(1.235, 64.556, 3);
            entity.location.setYaw(-170f);
            entity.location.setPitch(45.36f);
            String handle = h.handles.issue(entity.entity);
            JsonArray params = c.getAsJsonArray("params").deepCopy();
            String symbolic = params.get(0).getAsString();
            if (!symbolic.equals("mcr_eh_unknown")) {
                params.set(0, new com.google.gson.JsonPrimitive(handle));
            }
            String method = c.get("method").getAsString();
            switch (id) {
                case "B8-E01" -> {
                    h.commands.handleGetPose(params);
                    assertPose(c.getAsJsonObject("result"), h.context.result, id);
                    assertEquals(0, h.context.setterWorkCalls, id);
                }
                case "B8-E02" -> {
                    h.commands.handleSetPose(params);
                    assertPose(c.getAsJsonObject("result"), h.context.result, id);
                    assertEquals(c.get("teleport_calls").getAsInt(), entity.teleports.get(), id);
                    assertEquals(c.get("work_cost").getAsLong(), h.context.lastSetterUnits, id);
                    assertEquals(EntityHandleRegistry.ResolveStatus.ACTIVE, h.handles.resolve(handle).status(), id);
                    assertEquals(c.get("issued_dimension_after_success").getAsString(),
                            DimensionResolver.canonical(entity.location.getWorld()), id);
                }
                case "B8-E03" -> {
                    entity.teleportResult.set(false);
                    h.commands.handleSetPose(params);
                    assertEquals(c.get("reason").getAsString(), h.context.reason, id);
                    assertEquals(c.get("work_cost").getAsLong(), h.context.lastSetterUnits, id);
                }
                case "B8-E04" -> {
                    h.context.result = "unset";
                    h.commands.handleRemove(params);
                    assertNull(h.context.result, id);
                    assertNull(h.context.reason, id);
                    assertEquals(c.get("work_cost").getAsLong(), h.context.lastSetterUnits, id);
                    h.commands.handleRemove(params);
                    assertEquals(c.get("next_reason").getAsString(), h.context.reason, id);
                }
                case "B8-E05" -> {
                    h.commands.handleGetPose(params);
                    assertEquals(c.get("reason").getAsString(), h.context.reason, id);
                }
                case "B8-E06" -> {
                    entity.dead.set(true);
                    h.commands.handleRemove(params);
                    assertEquals(c.get("first_reason").getAsString(), h.context.reason, id);
                    h.commands.handleRemove(params);
                    assertEquals(c.get("next_reason").getAsString(), h.context.reason, id);
                }
                case "B8-E07" -> {
                    entity.location.setWorld(NETHER);
                    h.commands.handleGetPose(params);
                    assertEquals(c.get("first_reason").getAsString(), h.context.reason, id);
                    h.commands.handleGetPose(params);
                    assertEquals(c.get("next_reason").getAsString(), h.context.reason, id);
                }
                default -> fail("unmapped case " + id + " " + method);
            }
        }
    }

    // ---- particle Stage 2 ----

    @Test
    void particleStage2Cases() throws Exception {
        for (JsonElement element : root().getAsJsonObject("particle_stage_2").getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            String id = c.get("id").getAsString();
            ParticleHarness h = new ParticleHarness();
            if (c.has("self_authenticated") && !c.get("self_authenticated").getAsBoolean()) {
                h.bound = null;
            }
            if (c.has("chunk_preparation")) {
                h.chunkLoads = false;
            }
            if (c.has("builder_spawn")) {
                h.spawnFails = true;
            }
            JsonArray params;
            if (c.has("params")) {
                params = c.getAsJsonArray("params");
            } else {
                params = JsonParser.parseString("[1,2,3,0,0,0,null,0,1]").getAsJsonArray();
                params.set(6, c.get("particle"));
            }
            h.spawn(params);

            if (c.has("reason")) {
                assertEquals(c.get("reason").getAsString(), h.context.reason, id);
                assertNull(h.context.result, id);
            } else {
                assertNull(h.context.reason, id);
                assertEquals(c.get("result").getAsInt(), h.context.result, id);
            }
            if (c.has("effective_receiver")) {
                assertNull(h.receivers, id + ": world receiver is world-wide");
            }
            if (c.has("receiver_count")) {
                assertEquals(c.get("receiver_count").getAsInt(), ((List<?>) h.receivers).size(), id);
            }
            if (c.has("builder_force")) {
                assertEquals(c.get("builder_force").getAsBoolean(), h.force, id);
            }
            if (c.has("effective_force")) {
                assertEquals(c.get("effective_force").getAsBoolean(), h.force, id);
            }
            if (c.has("work_refunded")) {
                assertEquals(1, h.context.workCalls, id + ": work was admitted and not refunded");
            }
        }
    }

    // ---- harness ----

    private static JsonObject root() throws IOException {
        return JsonParser.parseString(new String(fixtureBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream stream = EntityParticleFixtureContractTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IOException("missing " + FIXTURE);
            }
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<Double> doubles(Object values) {
        List<Double> out = new ArrayList<>();
        if (values instanceof JsonArray array) {
            array.forEach(value -> out.add(value.getAsDouble()));
        } else {
            for (Object value : (List<?>) values) {
                out.add(((BigDecimal) value).doubleValue());
            }
        }
        return out;
    }

    private static void assertPose(JsonObject expected, Object actual, String id) {
        Map<?, ?> pose = (Map<?, ?>) actual;
        assertEquals(List.of("dimension", "pos", "yaw", "pitch"), new ArrayList<>(pose.keySet()), id);
        assertEquals(expected.get("dimension").getAsString(), pose.get("dimension"), id);
        assertEquals(doubles(expected.getAsJsonArray("pos")), doubles(pose.get("pos")), id);
        assertEquals(expected.get("yaw").getAsDouble(), ((BigDecimal) pose.get("yaw")).doubleValue(), 1e-9, id);
        assertEquals(expected.get("pitch").getAsDouble(), ((BigDecimal) pose.get("pitch")).doubleValue(), 1e-9, id);
    }

    private static final class Registry {
        final Map<UUID, Entity> byId = new HashMap<>();
        final EntityHandleRegistry handles;
        private int next;

        Registry(int capacity) {
            handles = new EntityHandleRegistry(capacity, new SecureRandom(), byId::get);
        }

        FakeEntity entity(World world) {
            return entity(new UUID(0, ++next), new Location(world, 0, 0, 0), true);
        }

        FakeEntity entity(UUID id, Location location, boolean valid) {
            FakeEntity fake = new FakeEntity(id, location);
            fake.dead.set(!valid);
            byId.put(id, fake.entity);
            return fake;
        }
    }

    private static final class NearbyHarness {
        final Registry registry;
        final EntityHandleRegistry handles;
        final Context context;
        final AtomicInteger searches = new AtomicInteger();
        final List<Entity> candidates = new ArrayList<>();
        final EntityLifecycleCommands commands;

        NearbyHarness(JsonObject c) {
            registry = new Registry(256);
            handles = registry.handles;
            JsonArray originArray = c.has("origin") ? c.getAsJsonArray("origin") : null;
            Location origin = originArray == null ? new Location(OVERWORLD, 0, 0, 0)
                    : new Location(OVERWORLD, originArray.get(0).getAsDouble(),
                    originArray.get(1).getAsDouble(), originArray.get(2).getAsDouble());
            context = new Context(origin);
            if (c.has("build_bounds_xz")) {
                JsonArray b = c.getAsJsonArray("build_bounds_xz");
                context.bounds = new double[]{b.get(0).getAsDouble(), b.get(1).getAsDouble(),
                        b.get(2).getAsDouble(), b.get(3).getAsDouble()};
            }
            if (c.has("max_work_per_request")) {
                context.maxPerRequest = c.get("max_work_per_request").getAsLong();
            }
            for (String budget : List.of("session_work_remaining", "player_work_remaining", "global_work_remaining")) {
                if (c.has(budget)) {
                    context.remaining = c.get(budget).getAsLong();
                }
            }
            YamlConfiguration config = new YamlConfiguration();
            if (c.has("policy")) {
                config.set("entities.nearby_max_radius", c.getAsJsonObject("policy").get("max_radius").getAsInt());
                config.set("entities.nearby_max_entities", c.getAsJsonObject("policy").get("max_entities").getAsInt());
            }
            if (c.has("candidates")) {
                for (JsonElement element : c.getAsJsonArray("candidates")) {
                    JsonObject candidate = element.getAsJsonObject();
                    UUID uuid = UUID.fromString(candidate.get("uuid").getAsString());
                    JsonArray pos = candidate.getAsJsonArray("absolute_pos");
                    World world = candidate.has("dimension")
                            && candidate.get("dimension").getAsString().equals("minecraft:the_nether")
                            ? NETHER : OVERWORLD;
                    Location location = new Location(world, pos.get(0).getAsDouble(),
                            pos.get(1).getAsDouble(), pos.get(2).getAsDouble());
                    if (candidate.has("player") && candidate.get("player").getAsBoolean()) {
                        candidates.add(player(location));
                        continue;
                    }
                    boolean valid = !candidate.has("valid") || candidate.get("valid").getAsBoolean();
                    FakeEntity fake = registry.byId.containsKey(uuid)
                            ? null : registry.entity(uuid, location, valid);
                    candidates.add(fake == null ? registry.byId.get(uuid) : fake.entity);
                }
            }
            DimensionResolver dimensions = new DimensionResolver(key -> switch (key.toString()) {
                case "minecraft:overworld" -> OVERWORLD;
                case "minecraft:the_nether" -> NETHER;
                default -> null;
            });
            commands = new EntityLifecycleCommands(context, handles, dimensions, RuntimePolicy.from(config),
                    (center, radius) -> {
                        searches.incrementAndGet();
                        return new ArrayList<>(candidates);
                    });
        }

        List<String> resultUuids() {
            List<String> uuids = new ArrayList<>();
            for (Object item : (List<?>) context.result) {
                String handle = (String) ((Map<?, ?>) item).get("handle");
                uuids.add(handles.resolve(handle).entity().getUniqueId().toString());
            }
            return uuids;
        }
    }

    private static final class FakeEntity {
        final Location location;
        final AtomicBoolean dead = new AtomicBoolean();
        final AtomicBoolean teleportResult = new AtomicBoolean(true);
        final AtomicInteger teleports = new AtomicInteger();
        final Entity entity;

        FakeEntity(UUID id, Location location) {
            this.location = location;
            this.entity = proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getType" -> EntityType.COW;
                case "getWorld" -> location.getWorld();
                case "getLocation" -> location.clone();
                case "isValid", "isInWorld" -> !dead.get();
                case "isDead" -> dead.get();
                case "remove" -> {
                    dead.set(true);
                    yield null;
                }
                case "teleport" -> {
                    teleports.incrementAndGet();
                    if (!teleportResult.get()) {
                        yield false;
                    }
                    Location target = (Location) args[0];
                    location.setWorld(target.getWorld());
                    location.set(target.getX(), target.getY(), target.getZ());
                    location.setYaw(target.getYaw());
                    location.setPitch(target.getPitch());
                    yield true;
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static final class Context implements B7CommandContext {
        private final Location origin;
        double[] bounds;
        long maxPerRequest = Long.MAX_VALUE;
        long remaining = Long.MAX_VALUE;
        int setterWorkCalls;
        long lastSetterUnits;
        Object result;
        String reason;

        Context(Location origin) {
            this.origin = origin;
        }

        @Override public UUID getBoundUuid() { return null; }
        @Override public UUID getConnectionEpoch() { return UUID.randomUUID(); }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() { return true; }
        @Override public boolean isWithinBuildRange(Location target) {
            if (bounds == null) {
                return true;
            }
            return target.getX() >= bounds[0] && target.getX() <= bounds[1]
                    && target.getZ() >= bounds[2] && target.getZ() <= bounds[3];
        }
        @Override public WorkAdmission.Result admitWork(int units) { return WorkAdmission.Result.ACCEPTED; }
        @Override public boolean admitSetterWork(long units) {
            setterWorkCalls++;
            lastSetterUnits = units;
            if (units > maxPerRequest) {
                reason = "work_limit_exceeded";
                return false;
            }
            if (units > remaining) {
                reason = "backpressure";
                return false;
            }
            return true;
        }
        @Override public boolean rejectTemporaryBackpressure() { return false; }
        @Override public void respondResult(Object value) {
            result = value;
            reason = null;
        }
        @Override public void respondError(int code, String reason, Map<String, Object> extraData) {
            this.reason = reason;
        }
    }

    private static final class ParticleHarness {
        UUID bound = UUID.randomUUID();
        boolean chunkLoads = true;
        boolean spawnFails;
        Object receivers;
        Boolean force;
        final ParticleContext context = new ParticleContext();
        final BlockData stone = proxy(BlockData.class, (p, m, a) -> defaultValue(m.getReturnType()));
        final Player player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "isOnline" -> true;
            default -> defaultValue(method.getReturnType());
        });

        void spawn(JsonArray params) {
            World world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "isChunkLoaded" -> chunkLoads;
                case "loadChunk" -> chunkLoads;
                case "spawnParticle" -> {
                    if (spawnFails) {
                        throw new IllegalStateException("spawn failure");
                    }
                    receivers = args[1];
                    force = (Boolean) args[12];
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            context.origin = new Location(world, 0, 64, 0);
            BlockCodec codec = new BlockCodec(id -> "minecraft:stone".equals(id) ? Map.of() : null, s -> stone);
            new WorldB5Commands(context, new EntityHandleRegistry(4), RuntimePolicy.from(new YamlConfiguration()),
                    EntityParticleFixtureContractTest::particle, codec, () -> bound, id -> player)
                    .handleSpawnParticle(params);
        }
    }

    private static final class ParticleContext implements WorldCommandContext {
        Location origin;
        int workCalls;
        Object result;
        String reason;

        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() { return true; }
        @Override public boolean isWithinBuildRange(Location target) { return true; }
        @Override public WorkAdmission.Result admitWork(int units) {
            workCalls++;
            return WorkAdmission.Result.ACCEPTED;
        }
        @Override public void respondResult(Object value) { result = value; }
        @Override public void respondError(int code, String reason, Map<String, Object> data) {
            this.reason = reason;
        }
    }

    /** Registry lookup needs a server; resolve the fixture's registered particles by canonical key. */
    private static Particle particle(String id) {
        for (Particle candidate : List.of(Particle.FLAME, Particle.DUST, Particle.BLOCK,
                Particle.DUST_COLOR_TRANSITION)) {
            if (candidate.getKey().toString().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private static Player player(Location location) {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> location.clone();
            case "isValid", "isInWorld" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static World world(String value) {
        NamespacedKey key = NamespacedKey.minecraft(value);
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "FixtureProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return invocation.invoke(proxy, method, args == null ? new Object[0] : args);
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
