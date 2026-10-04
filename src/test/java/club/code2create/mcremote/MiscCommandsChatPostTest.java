package club.code2create.mcremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Exercises the production chat handler and RemoteSession's real response encoding / queue. */
class MiscCommandsChatPostTest {
    @Test
    void idRequestBroadcastsMessageAndReturnsExplicitNull() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Fixture fixture = dispatch("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"chat.post\","
                    + "\"params\":[\"こんにちは\"]}");

            JsonObject response = fixture.response();
            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertEquals(17, response.get("id").getAsInt());
            assertTrue(response.has("result"));
            assertTrue(response.get("result").isJsonNull());
            assertFalse(response.has("error"));
            assertTrue(fixture.frames.isEmpty());
            bukkit.verify(() -> Bukkit.broadcast(Component.text("こんにちは")));
        }
    }

    @Test
    void notificationBroadcastsWithoutAResponseFrame() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Fixture fixture = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"chat.post\","
                    + "\"params\":[\"hello\"]}");

            assertTrue(fixture.frames.isEmpty());
            bukkit.verify(() -> Bukkit.broadcast(Component.text("hello")));
        }
    }

    @Test
    void missingAndEmptyMessagesKeepTheInvalidParamsResponse() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (String params : new String[]{"[]", "[\"\"]"}) {
                Fixture fixture = dispatch("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"chat.post\","
                        + "\"params\":" + params + "}");

                JsonObject response = fixture.response();
                assertEquals(18, response.get("id").getAsInt());
                assertFalse(response.has("result"));
                JsonObject error = response.getAsJsonObject("error");
                assertEquals(-32602, error.get("code").getAsInt());
                assertEquals("invalid_params", error.getAsJsonObject("data").get("reason").getAsString());
                assertTrue(fixture.frames.isEmpty());
            }
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void invalidNotificationDoesNotBroadcastOrReply() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (String params : new String[]{"[]", "[\"\"]"}) {
                Fixture fixture = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"chat.post\","
                        + "\"params\":" + params + "}");
                assertTrue(fixture.frames.isEmpty());
            }
            bukkit.verifyNoInteractions();
        }
    }

    static Fixture dispatch(String request) throws Exception {
        ParsedCommand parsed = new CommandParser().parse(request);
        // Avoid the live socket constructor; result/error methods, serializer, and enqueue stay real.
        RemoteSession session = mock(RemoteSession.class, CALLS_REAL_METHODS);
        ConnectionFrameQueue frames = new ConnectionFrameQueue(8);
        setField(session, "activeId", parsed.getId());
        setField(session, "outQueue", frames);
        setField(session, "queueLock", new Object());
        new MiscCommands(session).handleChatPost(parsed.getArgs());
        return new Fixture(frames);
    }

    private static void setField(RemoteSession session, String name, Object value) throws Exception {
        Field field = RemoteSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    record Fixture(ConnectionFrameQueue frames) {
        JsonObject response() {
            String frame = frames.poll();
            assertNotNull(frame, "production handler must enqueue one response frame");
            return JsonParser.parseString(frame).getAsJsonObject();
        }
    }
}
