package club.code2create.mcremote;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionTickRotationTest {
    @Test void samePlayerConnectionsRotateThroughSharedBudgetWithoutStarvation() throws Exception {
        McRemote plugin = mock(McRemote.class);
        var work = new WorkAdmission(RuntimePolicy.from(new MemoryConfiguration()));
        set(plugin, "workAdmission", work);
        set(plugin, "lightningRateAdmission", mock(LightningRateAdmission.class));
        set(plugin, "soundRateAdmission", mock(SoundRateAdmission.class));
        set(plugin, "nextConnectionLimitLog", Long.MAX_VALUE);
        var sessions = new CopyOnWriteArrayList<RemoteSession>();
        UUID player = UUID.randomUUID();
        int[] progressed = new int[3];
        for (int i = 0; i < 3; i++) {
            int index = i;
            UUID epoch = UUID.randomUUID();
            RemoteSession session = mock(RemoteSession.class);
            doAnswer(call -> { progressed[index] += work.reserve(epoch, player, 32768); return null; })
                    .when(session).tick();
            sessions.add(session);
        }
        set(plugin, "sessions", sessions);
        Class<?> type = Class.forName(McRemote.class.getName() + "$TickHandler");
        var constructor = type.getDeclaredConstructor(McRemote.class);
        constructor.setAccessible(true);
        Runnable scheduler = (Runnable) constructor.newInstance(plugin);
        scheduler.run();
        assertArrayEquals(new int[]{4096, 4096, 0}, progressed);
        scheduler.run();
        assertArrayEquals(new int[]{4096, 8192, 4096}, progressed);
        scheduler.run();
        assertArrayEquals(new int[]{8192, 8192, 8192}, progressed);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = McRemote.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
