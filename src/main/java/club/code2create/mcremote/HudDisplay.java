package club.code2create.mcremote;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** One owned boss bar per player, updated in place and removed on quit/disable. Main thread only. */
final class HudDisplay implements AutoCloseable {
    private record Shown(Player player, BossBar bar) {}
    private final Map<UUID, Shown> shown = new HashMap<>();

    void update(Player player, HudFrame frame) {
        UUID id = player.getUniqueId();
        Shown active = shown.get(id);
        if (active != null && active.player() != player) {
            hide(id);
            active = null;
        }
        Component text = frame.render();
        if (active == null) {
            BossBar bar = BossBar.bossBar(text, 1, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            shown.put(id, new Shown(player, bar));
            player.showBossBar(bar);
        } else if (!active.bar().name().equals(text)) {
            active.bar().name(text);
        }
    }

    void hide(UUID playerId) {
        Shown active = shown.remove(playerId);
        if (active != null) active.player().hideBossBar(active.bar());
    }

    @Override
    public void close() {
        for (Shown active : shown.values()) active.player().hideBossBar(active.bar());
        shown.clear();
    }
}
