package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class B5PluginRuntimePolicyFixtureTest {
    private static final String FIXTURE = "/fixtures/b5-plugin-runtime-policy-v23.json";

    @Test
    void fixtureKeepsHistoricalCandidateRuntimePolicy() throws IOException {
        JsonObject policy = fixture().getAsJsonObject("runtime_policy");
        assertEquals(RuntimePolicy.DEFAULT_CONNECTION_QUEUE_CAPACITY,
                integer(policy, "connection_fifo_capacity"));
        assertEquals(RuntimePolicy.DEFAULT_CONNECTION_RESPONSE_QUEUE_CAPACITY,
                integer(policy, "connection_response_queue_capacity"));
        assertEquals(RuntimePolicy.DEFAULT_EVENT_RING_CAPACITY,
                integer(policy, "event_ring_capacity"));
        assertEquals(RuntimePolicy.DEFAULT_EVENT_RING_BYTES,
                integer(policy, "event_ring_bytes"));
        assertEquals(RuntimePolicy.DEFAULT_EVENT_POLL_DEFAULT,
                integer(policy, "event_poll_default"));
        assertEquals(RuntimePolicy.DEFAULT_EVENT_POLL_LIMIT,
                integer(policy, "event_poll_limit"));
        assertEquals(RuntimePolicy.DEFAULT_ENTITY_HANDLE_CAPACITY,
                integer(policy, "entity_handle_capacity"));
        assertEquals(RuntimePolicy.DEFAULT_MAX_PARTICLE_COUNT,
                integer(policy, "max_particle_count"));
        // The b5 fixture keeps its published policy; later defaults do not rewrite that history.
        int candidateWorkLimit = integer(policy, "max_work_per_request");
        assertEquals(4096, candidateWorkLimit);
        YamlConfiguration candidateConfig = new YamlConfiguration();
        candidateConfig.set("work.per_request", candidateWorkLimit);
        assertEquals(candidateWorkLimit, RuntimePolicy.from(candidateConfig).maxWorkPerRequest());
        assertEquals(RuntimePolicy.DEFAULT_SESSION_WORK_PER_TICK,
                integer(policy, "session_work_per_tick"));
        assertEquals(RuntimePolicy.DEFAULT_PLAYER_WORK_PER_TICK,
                integer(policy, "player_work_per_tick"));
        assertEquals(RuntimePolicy.DEFAULT_GLOBAL_WORK_PER_TICK,
                integer(policy, "global_work_per_tick"));
        assertEquals(RemoteSession.MAX_EVENT_POLL_RESPONSE_BYTES,
                integer(policy, "max_compact_poll_response_bytes"));
        assertEquals(RemoteSession.resultPayloadBudget(
                        Integer.MAX_VALUE, RemoteSession.MAX_EVENT_POLL_RESPONSE_BYTES),
                integer(policy, "max_poll_result_payload_bytes"));
    }

    @Test
    void fixtureCasesAreAcceptedOrRejectedByTheProductionParser() throws IOException {
        JsonObject poll = fixture().getAsJsonObject("events_poll");
        for (var element : poll.getAsJsonArray("accepted")) {
            JsonObject item = element.getAsJsonObject();
            EventCommands.PollRequest request = EventCommands.parseRequest(
                    item.get("params"),
                    RuntimePolicy.DEFAULT_EVENT_POLL_DEFAULT,
                    RuntimePolicy.DEFAULT_EVENT_POLL_LIMIT);
            assertEquals(item.get("effective_max_events").getAsInt(), request.maxEvents());
        }
        for (var params : poll.getAsJsonArray("rejected")) {
            assertThrows(IllegalArgumentException.class, () -> EventCommands.parseRequest(
                    params,
                    RuntimePolicy.DEFAULT_EVENT_POLL_DEFAULT,
                    RuntimePolicy.DEFAULT_EVENT_POLL_LIMIT));
        }
    }

    private static JsonObject fixture() throws IOException {
        try (var stream = B5PluginRuntimePolicyFixtureTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IOException("missing fixture " + FIXTURE);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static int integer(JsonObject object, String key) {
        return object.get(key).getAsInt();
    }
}
