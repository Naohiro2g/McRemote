package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct consumer of the Scratch-owned protocol 23.1 direction/lightning fixture. */
class DirectionLightningFixtureContractTest {
    private static final String FIXTURE = "/fixtures/direction-lightning-v23.1.json";
    private static final String FIXTURE_SHA256 =
            "586d24bf40136eec31f1827f23ef5b317f15100a17a635d7fe9f165e0af40dce";

    @Test
    void exactSuccessorOwnerBytesAndAll93CasesMapToProductionSurfaces() throws Exception {
        byte[] bytes = fixtureBytes();
        assertEquals(20_367, bytes.length);
        assertEquals(FIXTURE_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));

        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("mcremote.direction-lightning.v23.1", root.get("schema").getAsString());
        assertEquals(ProtocolInfo.PROTOCOL, root.get("protocol").getAsString());

        List<FixtureCase> cases = new ArrayList<>();
        collectCases(root, "", cases);
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Integer> surfaces = new HashMap<>();
        for (FixtureCase fixtureCase : cases) {
            assertTrue(ids.add(fixtureCase.id()), "duplicate case id " + fixtureCase.id());
            surfaces.merge(productionSurface(fixtureCase.path()), 1, Integer::sum);
        }

        assertEquals(93, cases.size());
        assertEquals(93, ids.size());
        assertEquals(Map.of(
                "DirectionValue/WireParams", 22,
                "DirectionCommands", 9,
                "EntityHandleRegistry/DirectionCommands", 8,
                "SessionAdmission", 16,
                "LightningCommands", 19,
                "LightningRateAdmission", 8,
                "WorkAdmission", 5,
                "WorldB5Commands/ParticleBuilder", 6), surfaces);
    }

    @Test
    void sessionAdmissionCasesDriveHelloSnapshotAndLifecycleRules() throws IOException {
        JsonObject admission = fixture().getAsJsonObject("session_admission");
        JsonObject snapshotCase = admission.getAsJsonArray("hello_snapshot_cases")
                .get(0).getAsJsonObject();
        ConstructionPermissions snapshot = permissions(
                snapshotCase.getAsJsonObject("session_snapshot"));
        assertEquals(snapshotCase.getAsJsonObject("hello_permissions").get("online").getAsBoolean(),
                RemoteSession.buildPermissions(snapshot).get("online"));
        assertEquals(snapshotCase.getAsJsonObject("hello_permissions").get("offline").getAsBoolean(),
                RemoteSession.buildPermissions(snapshot).get("offline"));
        assertEquals(snapshotCase.getAsJsonObject("hello_permissions").get("buildRange").getAsInt(),
                RemoteSession.buildPermissions(snapshot).get("buildRange"));

        for (JsonElement element : admission.getAsJsonArray("admission_matrix")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject values = item.getAsJsonObject("permissions");
            ConstructionPermissions candidate = new ConstructionPermissions(
                    values.get("online").getAsBoolean(), values.get("offline").getAsBoolean(), 100);
            boolean online = item.get("player_state").getAsString().equals("online");
            assertEquals(item.get("accepted").getAsBoolean(),
                    RemoteSession.helloConstructionAllowed(true, candidate, online),
                    item.get("id").getAsString());
        }

        for (JsonElement element : admission.getAsJsonArray("transition_cases")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject values = item.getAsJsonObject("permissions");
            ConstructionPermissions candidate = new ConstructionPermissions(
                    values.get("online").getAsBoolean(), values.get("offline").getAsBoolean(), 100);
            boolean closed = item.get("event").getAsString().equals("PlayerQuitEvent")
                    ? candidate.closesOnQuit() : candidate.closesOnJoin();
            assertEquals(item.get("session_closed").getAsBoolean(), closed,
                    item.get("id").getAsString());
        }

        JsonArray changes = admission.getAsJsonArray("snapshot_change_cases");
        ConstructionPermissions existing = permissions(
                changes.get(0).getAsJsonObject().getAsJsonObject("session_snapshot"));
        assertEquals(new ConstructionPermissions(true, false, 100), existing, "B7-A30");
        assertEquals(100, existing.buildRange(), "B7-A31");
        ConstructionPermissions refreshed = permissions(
                changes.get(2).getAsJsonObject().getAsJsonObject("next_snapshot"));
        assertEquals(new ConstructionPermissions(true, true, 200), refreshed, "B7-A32");
    }

    @Test
    void directionVectorCasesDriveProductionParsingNormalizationAndWireNumbers() throws IOException {
        JsonObject direction = fixture().getAsJsonObject("direction");
        JsonObject output = direction.getAsJsonObject("output");
        assertEquals(6, output.get("decimal_places").getAsInt());
        assertEquals("HALF_UP", output.get("rounding").getAsString());

        for (JsonElement element : direction.getAsJsonArray("valid_vectors")) {
            JsonObject item = element.getAsJsonObject();
            JsonArray values;
            if (item.has("input")) {
                values = item.getAsJsonArray("input");
            } else if (item.has("input_json")) {
                values = JsonParser.parseString(item.get("input_json").getAsString()).getAsJsonArray();
            } else {
                values = item.getAsJsonArray("post_read");
            }
            List<BigDecimal> actual = item.has("post_read")
                    ? List.of(
                            WireNumbers.direction(values.get(0).getAsDouble()),
                            WireNumbers.direction(values.get(1).getAsDouble()),
                            WireNumbers.direction(values.get(2).getAsDouble()))
                    : DirectionValue.parse(values, 0).wire();
            assertDecimalVector(item.getAsJsonArray("result"), actual, item.get("id").getAsString());
            double norm = actual.stream()
                    .mapToDouble(value -> value.doubleValue() * value.doubleValue()).sum();
            assertTrue(Math.abs(Math.sqrt(norm) - 1.0) <= output.get("norm_tolerance").getAsDouble(),
                    item.get("id").getAsString());
        }

        for (JsonElement element : direction.getAsJsonArray("invalid_vectors")) {
            JsonObject item = element.getAsJsonObject();
            JsonArray params = directionParams(item);
            String actual;
            try {
                DirectionValue.parse(WireParams.positional(params, 3), 0);
                actual = null;
            } catch (DirectionValue.ZeroDirectionException e) {
                actual = "zero_direction";
            } catch (IllegalArgumentException e) {
                actual = "invalid_params";
            }
            assertEquals(item.get("reason").getAsString(), actual, item.get("id").getAsString());
        }
    }

    @Test
    void directionMethodAndHandleCasesDriveRegisteredHandlersAndRegistryLifecycle() throws IOException {
        JsonObject root = fixture();
        JsonObject direction = root.getAsJsonObject("direction");
        JsonArray setParams = findById(direction.getAsJsonArray("method_cases"), "B7-D31")
                .getAsJsonArray("params");

        for (JsonElement element : direction.getAsJsonArray("method_cases")) {
            JsonObject item = element.getAsJsonObject();
            DirectionHarness harness = new DirectionHarness();
            String id = item.get("id").getAsString();
            switch (id) {
                case "B7-D30" -> {
                    harness.invoke(item.get("method").getAsString(), item.getAsJsonArray("params"));
                    assertDecimalVector(item.getAsJsonArray("result"), castWire(harness.context.result), id);
                    assertEquals(item.get("work_cost").getAsInt(), harness.context.workCalls);
                }
                case "B7-D31" -> {
                    Location before = harness.playerLocation.clone();
                    harness.invoke(item.get("method").getAsString(), item.getAsJsonArray("params"));
                    assertEquals(item.get("work_cost").getAsInt(), harness.context.workUnits);
                    assertNotNull(harness.context.result);
                    assertPositionAndWorld(before, harness.playerLocation, id);
                }
                case "B7-D32" -> {
                    harness.context.boundUuid = null;
                    harness.invoke(item.get("method").getAsString(), new JsonArray());
                    assertEquals(item.get("reason").getAsString(), harness.context.reason);
                }
                case "B7-D33" -> {
                    harness.online = false;
                    harness.invoke(item.get("method").getAsString(), setParams);
                    assertEquals(item.get("reason").getAsString(), harness.context.reason);
                }
                case "B7-D34" -> {
                    harness.context.construction = false;
                    harness.invoke(item.get("method").getAsString(), setParams);
                    assertEquals(item.get("reason").getAsString(), harness.context.reason);
                }
                case "B7-D35" -> {
                    harness.entityLocation.setYaw(-90.0f);
                    JsonArray params = new JsonArray();
                    params.add(harness.issueEntity());
                    harness.invoke(item.get("method").getAsString(), params);
                    assertDecimalVector(item.getAsJsonArray("result"), castWire(harness.context.result), id);
                    assertEquals(item.get("work_cost").getAsInt(), harness.context.workCalls);
                }
                case "B7-D36" -> {
                    JsonArray params = item.getAsJsonArray("params").deepCopy();
                    params.set(0, new com.google.gson.JsonPrimitive(harness.issueEntity()));
                    Location before = harness.entityLocation.clone();
                    harness.invoke(item.get("method").getAsString(), params);
                    assertEquals(item.get("work_cost").getAsInt(), harness.context.workUnits);
                    assertNotNull(harness.context.result);
                    assertPositionAndWorld(before, harness.entityLocation, id);
                }
                case "B7-D37" -> {
                    harness.failEntityRotation = true;
                    JsonArray params = findById(direction.getAsJsonArray("method_cases"), "B7-D36")
                            .getAsJsonArray("params").deepCopy();
                    params.set(0, new com.google.gson.JsonPrimitive(harness.issueEntity()));
                    harness.invoke(item.get("method").getAsString(), params);
                    assertEquals(item.get("reason").getAsString(), harness.context.reason);
                    assertEquals(1, harness.entityRotationCalls.get());
                }
                case "B7-D38" -> {
                    harness.failPlayerPostRead = true;
                    harness.invoke(item.get("method").getAsString(), setParams);
                    assertEquals(item.get("reason").getAsString(), harness.context.reason);
                    assertEquals(1, harness.playerRotationCalls.get());
                }
                default -> throw new AssertionError("unmapped direction method case " + id);
            }
        }

        JsonObject handles = root.getAsJsonObject("handles");
        JsonObject invalidType = handles.getAsJsonObject("invalid_type");
        DirectionHarness invalidHarness = new DirectionHarness();
        invalidHarness.invoke(invalidType.get("method").getAsString(), invalidType.getAsJsonArray("params"));
        assertEquals(invalidType.get("reason").getAsString(), invalidHarness.context.reason);

        for (JsonElement element : handles.getAsJsonArray("unresolved_strings")) {
            JsonObject item = element.getAsJsonObject();
            DirectionHarness harness = new DirectionHarness();
            JsonArray params = new JsonArray();
            params.add(item.get("handle").getAsString());
            harness.invoke("entity.getDirection", params);
            assertEquals(item.get("reason").getAsString(), harness.context.reason,
                    item.get("id").getAsString());
        }

        for (JsonElement element : handles.getAsJsonArray("terminal_transitions")) {
            JsonObject item = element.getAsJsonObject();
            DirectionHarness harness = new DirectionHarness();
            String handle = harness.issueEntity();
            if (item.get("name").getAsString().equals("dimension_changed")) {
                harness.lookup.set(entityProxy(
                        harness.entityId, new Location(world("the_nether"), 0, 0, 0),
                        new AtomicInteger(), false, false));
            } else {
                harness.lookup.set(null);
            }
            JsonArray params = new JsonArray();
            params.add(handle);
            harness.invoke("entity.getDirection", params);
            assertEquals(item.get("first_reason").getAsString(), harness.context.reason);
            assertEquals(0, harness.handles.size());
            harness.context.reason = null;
            harness.invoke("entity.getDirection", params);
            assertEquals(item.get("next_reason").getAsString(), harness.context.reason);
        }
    }

    @Test
    void lightningRateAndWorkCasesDriveProductionAdmissions() throws IOException {
        JsonObject lightning = fixture().getAsJsonObject("lightning");
        JsonObject ratePolicy = lightning.getAsJsonObject("rate_policy");
        LightningRuntimePolicy policy = new LightningRuntimePolicy(
                ratePolicy.get("window_ticks").getAsInt(),
                ratePolicy.get("window_ticks").getAsInt(),
                ratePolicy.get("global_same_tick_accepts").getAsInt(),
                ratePolicy.get("window_ticks").getAsInt(),
                ratePolicy.get("global_window_accepts").getAsInt());

        for (JsonElement element : lightning.getAsJsonArray("rate_cases")) {
            JsonObject item = element.getAsJsonObject();
            String id = item.get("id").getAsString();
            LightningRateAdmission admission = new LightningRateAdmission(policy);
            switch (id) {
                case "B7-L20" -> {
                    advanceTo(admission, item.get("tick").getAsInt());
                    assertAccepted(admission, id);
                }
                case "B7-L21", "B7-L22" -> {
                    int attempt = item.get("tick").getAsInt();
                    int first = id.equals("B7-L21") ? attempt - 19 : attempt - 20;
                    UUID connection = UUID.randomUUID();
                    UUID player = UUID.randomUUID();
                    advanceTo(admission, first);
                    assertEquals(LightningRateAdmission.Result.ACCEPTED,
                            admission.admit(connection, player));
                    advanceTicks(admission, attempt - first);
                    assertEquals(item.has("accepted") && item.get("accepted").getAsBoolean()
                                    ? LightningRateAdmission.Result.ACCEPTED
                                    : LightningRateAdmission.Result.BACKPRESSURE,
                            admission.admit(connection, player), id);
                }
                case "B7-L23" -> {
                    advanceTo(admission, item.get("tick").getAsInt());
                    UUID player = UUID.randomUUID();
                    assertEquals(LightningRateAdmission.Result.ACCEPTED,
                            admission.admit(UUID.randomUUID(), player));
                    assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                            admission.admit(UUID.randomUUID(), player));
                }
                case "B7-L24" -> {
                    advanceTo(admission, item.get("tick").getAsInt());
                    JsonArray expected = item.getAsJsonArray("accepted");
                    for (int index = 0; index < expected.size(); index++) {
                        assertEquals(expected.get(index).getAsBoolean()
                                        ? LightningRateAdmission.Result.ACCEPTED
                                        : LightningRateAdmission.Result.BACKPRESSURE,
                                admission.admit(UUID.randomUUID(), UUID.randomUUID()), id);
                    }
                }
                case "B7-L25" -> {
                    int current = -1;
                    for (JsonElement tick : item.getAsJsonArray("accepted_ticks")) {
                        advanceTicks(admission, tick.getAsInt() - current);
                        current = tick.getAsInt();
                        assertAccepted(admission, id);
                    }
                    advanceTicks(admission, item.get("attempt_tick").getAsInt() - current);
                    assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                            admission.admit(UUID.randomUUID(), UUID.randomUUID()), id);
                }
                case "B7-L26" -> {
                    advanceTo(admission, 0);
                    UUID player = UUID.randomUUID();
                    UUID secondConnection = UUID.randomUUID();
                    assertEquals(LightningRateAdmission.Result.ACCEPTED,
                            admission.admit(UUID.randomUUID(), player));
                    assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                            admission.admit(secondConnection, player));
                    assertEquals(LightningRateAdmission.Result.ACCEPTED,
                            admission.admit(secondConnection, UUID.randomUUID()), id);
                }
                case "B7-L27" -> {
                    advanceTo(admission, 0);
                    UUID connection = UUID.randomUUID();
                    UUID player = UUID.randomUUID();
                    assertEquals(LightningRateAdmission.Result.ACCEPTED,
                            admission.admit(connection, player));
                    assertEquals(LightningRateAdmission.Result.BACKPRESSURE,
                            admission.admit(connection, player), id);
                    assertFalse(item.get("rate_slots_refunded").getAsBoolean());
                }
                default -> throw new AssertionError("unmapped rate case " + id);
            }
        }

        JsonObject workPolicy = lightning.getAsJsonObject("work_policy");
        int cost = workPolicy.get("cost").getAsInt();
        assertEquals(LightningCommands.WORK_UNITS, cost);
        for (JsonElement element : lightning.getAsJsonArray("work_cases")) {
            JsonObject item = element.getAsJsonObject();
            String id = item.get("id").getAsString();
            int max = item.has("max_work_per_request")
                    ? item.get("max_work_per_request").getAsInt()
                    : workPolicy.get("distribution_max_work_per_request").getAsInt();
            int tickBudget = id.equals("B7-L33") ? cost - 1 : cost;
            WorkAdmission admission = new WorkAdmission(workPolicy(max, tickBudget));
            admission.beginTick();
            WorkAdmission.Result actual = admission.admit(UUID.randomUUID(), UUID.randomUUID(), cost);
            String expected = item.has("reason") ? item.get("reason").getAsString() : "accepted";
            assertEquals(expected, workReason(actual), id);
            if (id.equals("B7-L34")) {
                UUID connection = UUID.randomUUID();
                UUID player = UUID.randomUUID();
                admission = new WorkAdmission(workPolicy(max, cost));
                admission.beginTick();
                assertEquals(WorkAdmission.Result.ACCEPTED, admission.admit(connection, player, cost));
                assertEquals(WorkAdmission.Result.BACKPRESSURE, admission.admit(connection, player, cost));
                assertFalse(item.get("work_refunded").getAsBoolean());
            }
        }
    }

    @Test
    void lightningWirePermissionRequestAndPaperCasesDriveProductionHandler() throws IOException {
        JsonObject lightning = fixture().getAsJsonObject("lightning");
        final JsonArray baseline = lightning.getAsJsonArray("wire_cases").get(0)
                .getAsJsonObject().getAsJsonArray("params");

        for (JsonElement element : lightning.getAsJsonArray("wire_cases")) {
            JsonObject item = element.getAsJsonObject();
            LightningHarness harness = new LightningHarness();
            String id = item.get("id").getAsString();
            if (item.has("origin")) {
                harness.setOrigin(item.getAsJsonArray("origin"));
            }
            JsonArray params = item.has("params") ? item.getAsJsonArray("params") : baseline;
            if (id.equals("B7-L02")) {
                harness.notification = true;
            }
            harness.invoke(params);
            if (item.has("reason")) {
                assertEquals(item.get("reason").getAsString(), harness.reason, id);
            } else if (item.has("exact_target")) {
                assertLocation(item.getAsJsonArray("exact_target"), harness.strikeTarget, id);
                assertNull(harness.result);
            } else {
                assertEquals(0, harness.wireResponses, id);
            }
        }

        for (JsonElement element : lightning.getAsJsonArray("admission_cases")) {
            JsonObject item = element.getAsJsonObject();
            LightningHarness harness = new LightningHarness();
            String id = item.get("id").getAsString();
            JsonArray permissionParams = baseline;
            switch (id) {
                case "B7-L10" -> harness.boundUuid = null;
                default -> throw new AssertionError("unmapped permission case " + id);
            }
            harness.invoke(permissionParams);
            if (item.has("reason")) {
                assertEquals(item.get("reason").getAsString(), harness.reason, id);
            } else {
                assertEquals(item.get("accepted").getAsBoolean(), harness.strikeCalls.get() == 1, id);
            }
        }

        for (JsonElement element : lightning.getAsJsonArray("range_cases")) {
            JsonObject item = element.getAsJsonObject();
            LightningHarness harness = new LightningHarness();
            String id = item.get("id").getAsString();
            harness.invoke(rangeParams(item.getAsJsonArray("target")));
            if (item.has("reason")) {
                assertEquals(item.get("reason").getAsString(), harness.reason, id);
            } else {
                assertEquals(item.get("accepted").getAsBoolean(), harness.strikeCalls.get() == 1, id);
            }
        }

        for (JsonElement element : lightning.getAsJsonArray("request_mode_cases")) {
            JsonObject item = element.getAsJsonObject();
            LightningHarness harness = new LightningHarness();
            harness.notification = item.get("mode").getAsString().equals("notification");
            harness.workResult = item.get("reason").getAsString().equals("work_limit_exceeded")
                    ? WorkAdmission.Result.WORK_LIMIT_EXCEEDED : WorkAdmission.Result.BACKPRESSURE;
            if (item.has("action") && item.get("action").getAsString().equals("defer_fifo_head")) {
                assertThrows(CommandDeferredException.class, () -> harness.invoke(baseline));
                assertEquals(1, harness.deferred);
            } else {
                harness.invoke(baseline);
                assertEquals(item.get("reason").getAsString(), harness.reason);
                if (harness.notification) {
                    assertEquals(0, harness.wireResponses);
                }
            }
        }

        for (JsonElement element : lightning.getAsJsonArray("paper_cases")) {
            JsonObject item = element.getAsJsonObject();
            LightningHarness harness = new LightningHarness();
            String id = item.get("id").getAsString();
            switch (id) {
                case "B7-L50", "B7-L53", "B7-L54" -> harness.chunkLoaded = true;
                case "B7-L51" -> {
                    harness.chunkLoaded = false;
                    harness.chunkLoadResult = true;
                }
                case "B7-L52" -> {
                    harness.chunkLoaded = false;
                    harness.chunkLoadResult = false;
                }
                case "B7-L55" -> harness.throwOnStrike = true;
                default -> throw new AssertionError("unmapped paper case " + id);
            }
            harness.invoke(baseline);
            if (item.has("load_chunk_calls")) {
                assertEquals(item.get("load_chunk_calls").getAsInt(), harness.loadCalls.get(), id);
            }
            if (item.has("full_strike_calls")) {
                assertEquals(item.get("full_strike_calls").getAsInt(), harness.strikeCalls.get(), id);
            } else if (item.has("calls")) {
                assertEquals(item.get("calls").getAsInt(), harness.strikeCalls.get(), id);
            }
            if (item.has("reason")) {
                assertEquals(item.get("reason").getAsString(), harness.reason, id);
            }
            if (item.has("result") && item.get("result").isJsonNull()) {
                assertNull(harness.result, id);
            }
        }
    }

    @Test
    void particleRegressionCasesDriveProductionHandlerAndParticleBuilder() throws IOException {
        JsonObject particle = fixture().getAsJsonObject("particle_builder_regression");
        JsonArray cases = particle.getAsJsonArray("cases");
        JsonArray baseline = cases.get(0).getAsJsonObject().getAsJsonArray("params");
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            ParticleHarness harness = new ParticleHarness();
            String id = item.get("id").getAsString();
            JsonArray params = item.has("params") ? item.getAsJsonArray("params").deepCopy() : baseline.deepCopy();
            switch (id) {
                case "B7-P03" -> params.set(6,
                        new com.google.gson.JsonPrimitive(
                                "mcremote:" + item.get("name").getAsString()));
                case "B7-P04" -> params.set(6,
                        new com.google.gson.JsonPrimitive(Particle.DUST.getKey().toString()));
                case "B7-P05" -> harness.context.workResult = WorkAdmission.Result.BACKPRESSURE;
                case "B7-P06" -> harness.policy = particlePolicy(params.get(8).getAsInt() - 1);
                default -> { }
            }
            harness.invoke(params);
            if (item.has("reason")) {
                assertEquals(item.get("reason").getAsString(), harness.context.reason, id);
            } else {
                assertEquals(item.get("result").getAsInt(), harness.context.result, id);
                assertEquals(item.get("effective_force").getAsBoolean(), harness.force, id);
                assertEquals(particle.get("default_receiver").getAsString(), "world");
            }
        }
    }

    private static byte[] fixtureBytes() throws IOException {
        try (var stream = DirectionLightningFixtureContractTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) throw new IOException("missing fixture " + FIXTURE);
            return stream.readAllBytes();
        }
    }

    private static JsonObject fixture() throws IOException {
        return JsonParser.parseString(new String(fixtureBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void collectCases(JsonElement element, String path, List<FixtureCase> cases) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("id")) cases.add(new FixtureCase(object.get("id").getAsString(), path));
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectCases(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), cases);
            }
        } else if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                collectCases(child, path + "[" + index++ + "]", cases);
            }
        }
    }

    private static String productionSurface(String path) {
        if (path.startsWith("session_admission.")) return "SessionAdmission";
        if (path.startsWith("direction.valid_vectors") || path.startsWith("direction.invalid_vectors")) {
            return "DirectionValue/WireParams";
        }
        if (path.startsWith("direction.method_cases")) return "DirectionCommands";
        if (path.startsWith("handles.")) return "EntityHandleRegistry/DirectionCommands";
        if (path.startsWith("lightning.rate_cases")) return "LightningRateAdmission";
        if (path.startsWith("lightning.work_cases")) return "WorkAdmission";
        if (path.startsWith("lightning.")) return "LightningCommands";
        if (path.startsWith("particle_builder_regression.cases")) {
            return "WorldB5Commands/ParticleBuilder";
        }
        throw new AssertionError("unmapped fixture path " + path);
    }

    private static ConstructionPermissions permissions(JsonObject object) {
        return new ConstructionPermissions(
                object.get("onlineAllowed").getAsBoolean(),
                object.get("offlineAllowed").getAsBoolean(),
                object.get("buildRange").getAsInt());
    }

    private static JsonArray directionParams(JsonObject item) {
        if (item.has("params")) return item.getAsJsonArray("params");
        if (item.has("params_json")) {
            return JsonParser.parseString(item.get("params_json").getAsString()).getAsJsonArray();
        }
        JsonArray values = new JsonArray();
        for (JsonElement element : item.getAsJsonArray("direct_decoder_values")) {
            String value = element.getAsString();
            values.add(switch (value) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> element.getAsDouble();
            });
        }
        return values;
    }

    private static void assertDecimalVector(JsonArray expected, List<BigDecimal> actual, String id) {
        assertEquals(expected.size(), actual.size(), id);
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(0, new BigDecimal(expected.get(index).getAsString()).compareTo(actual.get(index)),
                    id + " component " + index);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BigDecimal> castWire(Object result) {
        return (List<BigDecimal>) result;
    }

    private static void assertPositionAndWorld(Location expected, Location actual, String id) {
        assertEquals(expected.getX(), actual.getX(), id);
        assertEquals(expected.getY(), actual.getY(), id);
        assertEquals(expected.getZ(), actual.getZ(), id);
        assertSame(expected.getWorld(), actual.getWorld(), id);
    }

    private static JsonObject findById(JsonArray cases, String id) {
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            if (item.get("id").getAsString().equals(id)) return item;
        }
        throw new AssertionError("missing fixture case " + id);
    }

    private static void advanceTo(LightningRateAdmission admission, int tick) {
        for (int current = -1; current < tick; current++) admission.beginTick();
    }

    private static void advanceTicks(LightningRateAdmission admission, int ticks) {
        for (int index = 0; index < ticks; index++) admission.beginTick();
    }

    private static void assertAccepted(LightningRateAdmission admission, String id) {
        assertEquals(LightningRateAdmission.Result.ACCEPTED,
                admission.admit(UUID.randomUUID(), UUID.randomUUID()), id);
    }

    private static String workReason(WorkAdmission.Result result) {
        return switch (result) {
            case ACCEPTED -> "accepted";
            case BACKPRESSURE -> "backpressure";
            case WORK_LIMIT_EXCEEDED -> "work_limit_exceeded";
        };
    }

    private static B5RuntimePolicy workPolicy(int max, int tickBudget) {
        return new B5RuntimePolicy(8, 8_000, 8, 8, 8, 1_000,
                max, tickBudget, tickBudget, tickBudget, 16, 16);
    }

    private static B5RuntimePolicy particlePolicy(int maxParticleCount) {
        return new B5RuntimePolicy(8, 8_000, 8, 8, 8, maxParticleCount,
                4_096, 4_096, 8_192, 32_768, 16, 16);
    }

    private static JsonArray rangeParams(JsonArray target) {
        JsonArray params = new JsonArray();
        if (target.size() == 2) {
            params.add(target.get(0));
            params.add(0);
            params.add(target.get(1));
        } else {
            params.add(target.get(0));
            params.add(target.get(1));
            params.add(target.get(2));
        }
        return params;
    }

    private static void assertLocation(JsonArray expected, Location actual, String id) {
        assertNotNull(actual, id);
        assertEquals(expected.get(0).getAsDouble(), actual.getX(), id);
        assertEquals(expected.get(1).getAsDouble(), actual.getY(), id);
        assertEquals(expected.get(2).getAsDouble(), actual.getZ(), id);
    }

    private static World world(String keyValue) {
        NamespacedKey key = NamespacedKey.minecraft(keyValue);
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Entity entityProxy(
            UUID uuid,
            Location location,
            AtomicInteger rotations,
            boolean failRotation,
            boolean failPostRead
    ) {
        return proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> {
                if (failPostRead && rotations.get() > 0) throw new IllegalStateException("post-read");
                yield location.clone();
            }
            case "isValid", "isInWorld" -> true;
            case "isDead" -> false;
            case "setRotation" -> {
                rotations.incrementAndGet();
                if (failRotation) throw new IllegalStateException("rotation");
                location.setYaw((float) args[0]);
                location.setPitch((float) args[1]);
                yield null;
            }
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
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }

    private record FixtureCase(String id, String path) { }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static final class FixtureContext implements B7CommandContext {
        private UUID boundUuid = UUID.randomUUID();
        private final UUID connection = UUID.randomUUID();
        private Location origin;
        private boolean construction = true;
        private boolean range = true;
        private WorkAdmission.Result workResult = WorkAdmission.Result.ACCEPTED;
        private int workCalls;
        private int workUnits;
        private Object result;
        private String reason;

        private FixtureContext(Location origin) { this.origin = origin; }
        @Override public UUID getBoundUuid() { return boundUuid; }
        @Override public UUID getConnectionEpoch() { return connection; }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() { return construction; }
        @Override public boolean isWithinBuildRange(Location target) { return range; }
        @Override public WorkAdmission.Result admitWork(int units) {
            workCalls++;
            workUnits = units;
            return workResult;
        }
        @Override public boolean admitSetterWork(long units) {
            workCalls++;
            workUnits = (int) units;
            return workResult == WorkAdmission.Result.ACCEPTED;
        }
        @Override public boolean rejectTemporaryBackpressure() {
            reason = "backpressure";
            return false;
        }
        @Override public void respondResult(Object value) { result = value; }
        @Override public void respondError(int code, String reason, Map<String, Object> data) {
            this.reason = reason;
        }
    }

    private static final class DirectionHarness {
        private final World world = world("overworld");
        private final Location playerLocation = new Location(world, 10, 64, 20);
        private final Location entityLocation = new Location(world, -4, 70, 8);
        private final UUID playerId = UUID.randomUUID();
        private final UUID entityId = UUID.randomUUID();
        private final AtomicInteger playerRotationCalls = new AtomicInteger();
        private final AtomicInteger entityRotationCalls = new AtomicInteger();
        private final AtomicReference<Entity> lookup = new AtomicReference<>();
        private final FixtureContext context = new FixtureContext(playerLocation);
        private final EntityHandleRegistry handles = new EntityHandleRegistry(
                4, new SecureRandom(), ignored -> lookup.get());
        private boolean online = true;
        private boolean failEntityRotation;
        private boolean failPlayerPostRead;
        private final Player player;
        private Entity entity;
        private final DirectionCommands commands;

        private DirectionHarness() {
            context.boundUuid = playerId;
            player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getWorld" -> world;
                case "getLocation" -> {
                    if (failPlayerPostRead && playerRotationCalls.get() > 0) {
                        throw new IllegalStateException("post-read");
                    }
                    yield playerLocation.clone();
                }
                case "isOnline" -> online;
                case "isValid", "isInWorld" -> true;
                case "isDead" -> false;
                case "setRotation" -> {
                    playerRotationCalls.incrementAndGet();
                    playerLocation.setYaw((float) args[0]);
                    playerLocation.setPitch((float) args[1]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            entity = entityProxy(entityId, entityLocation, entityRotationCalls, false, false);
            lookup.set(entity);
            commands = new DirectionCommands(context, handles, ignored -> player);
        }

        private String issueEntity() {
            if (failEntityRotation) {
                entity = entityProxy(entityId, entityLocation, entityRotationCalls, true, false);
                lookup.set(entity);
            }
            return handles.issue(entity);
        }

        private void invoke(String method, JsonArray params) {
            switch (method) {
                case "player.getDirection" -> commands.handlePlayerGet(params);
                case "player.setDirection" -> commands.handlePlayerSet(params);
                case "entity.getDirection" -> commands.handleEntityGet(params);
                case "entity.setDirection" -> commands.handleEntitySet(params);
                default -> throw new AssertionError("unexpected direction method " + method);
            }
        }
    }

    private static final class LightningHarness {
        private UUID boundUuid = UUID.randomUUID();
        private final UUID connection = UUID.randomUUID();
        private final World world;
        private Location origin;
        private boolean online = true;
        private boolean constructionAllowed = true;
        private boolean notification;
        private boolean chunkLoaded = true;
        private boolean chunkLoadResult = true;
        private boolean throwOnStrike;
        private WorkAdmission.Result workResult = WorkAdmission.Result.ACCEPTED;
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger strikeCalls = new AtomicInteger();
        private Location strikeTarget;
        private Object result;
        private String reason;
        private int wireResponses;
        private int deferred;
        private final LightningCommands commands;

        private LightningHarness() {
            world = proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
                case "isChunkLoaded" -> chunkLoaded;
                case "loadChunk" -> {
                    loadCalls.incrementAndGet();
                    yield chunkLoadResult;
                }
                case "strikeLightning" -> {
                    strikeCalls.incrementAndGet();
                    strikeTarget = ((Location) args[0]).clone();
                    if (throwOnStrike) throw new IllegalStateException("strike");
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            origin = new Location(world, 0, 0, 0);
            B7CommandContext context = new B7CommandContext() {
                @Override public UUID getBoundUuid() { return boundUuid; }
                @Override public UUID getConnectionEpoch() { return connection; }
                @Override public Location getOrigin() { return origin; }
                @Override public boolean hasConstructionPermission() {
                    return constructionAllowed;
                }
                @Override public boolean isWithinBuildRange(Location target) {
                    return RemoteSession.withinBuildRange(origin, target, 100);
                }
                @Override public WorkAdmission.Result admitWork(int units) { return workResult; }
                @Override public boolean admitSetterWork(long units) { return false; }
                @Override public boolean rejectTemporaryBackpressure() {
                    reason = "backpressure";
                    if (notification) {
                        deferred++;
                        throw CommandDeferredException.INSTANCE;
                    }
                    wireResponses++;
                    return false;
                }
                @Override public void respondResult(Object value) {
                    result = value;
                    if (!notification) wireResponses++;
                }
                @Override public void respondError(int code, String value, Map<String, Object> data) {
                    reason = value;
                    if (!notification) wireResponses++;
                }
            };
            LightningRuntimePolicy policy = new LightningRuntimePolicy(20, 20, 2, 20, 8);
            LightningRateAdmission rate = new LightningRateAdmission(policy);
            rate.beginTick();
            commands = new LightningCommands(context, rate, policy);
        }

        private void setOrigin(JsonArray values) {
            origin = new Location(world,
                    values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
        }

        private void invoke(JsonArray params) { commands.handleStrikeLightning(params); }
    }

    private static final class ParticleHarness {
        private final ParticleContext context;
        private B5RuntimePolicy policy = particlePolicy(1_000);
        private boolean force;
        private final World world;

        private ParticleHarness() {
            world = proxy(World.class, (proxy, method, args) -> {
                if (method.getName().equals("isChunkLoaded")) return true;
                if (method.getName().equals("spawnParticle") && args.length == 13) {
                    force = (boolean) args[12];
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
            context = new ParticleContext(new Location(world, 0, 0, 0));
        }

        private void invoke(JsonArray params) {
            new WorldB5Commands(
                    context,
                    new EntityHandleRegistry(4),
                    policy,
                    id -> switch (id) {
                        default -> {
                            if (id.equals(Particle.FLAME.getKey().toString())) yield Particle.FLAME;
                            if (id.equals(Particle.DUST.getKey().toString())) yield Particle.DUST;
                            yield null;
                        }
                    }).handleSpawnParticle(params);
        }
    }

    private static final class ParticleContext implements WorldCommandContext {
        private final Location origin;
        private WorkAdmission.Result workResult = WorkAdmission.Result.ACCEPTED;
        private Object result;
        private String reason;

        private ParticleContext(Location origin) { this.origin = origin; }
        @Override public Location getOrigin() { return origin; }
        @Override public boolean hasConstructionPermission() { return true; }
        @Override public boolean isWithinBuildRange(Location target) { return true; }
        @Override public WorkAdmission.Result admitWork(int units) { return workResult; }
        @Override public void respondResult(Object value) { result = value; }
        @Override public void respondError(int code, String reason, Map<String, Object> data) {
            this.reason = reason;
        }
    }
}
