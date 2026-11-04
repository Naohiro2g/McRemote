package club.code2create.mcremote;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Game-only first slice of the coordinate/time HUD. No wire methods or observer IDs. */
final class CoordinateHud implements AutoCloseable {
    static final long UPDATE_INTERVAL_TICKS = 10;

    private static final class State {
        boolean visible = true;
        HudSampler.Point point = HudSampler.Point.LEGS;
        boolean toggleOnSwap;
        final HudSelection selection = new HudSelection();

        State(boolean toggleOnSwap) {
            this.toggleOnSwap = toggleOnSwap;
        }
    }

    private final Function<Player, List<RemoteSession>> sessionsFor;
    private final HudDisplay display;
    private final boolean defaultToggleOnSwap;
    private final Map<UUID, State> states = new HashMap<>();

    CoordinateHud(Function<Player, List<RemoteSession>> sessionsFor, boolean defaultToggleOnSwap) {
        this(sessionsFor, new HudDisplay(), defaultToggleOnSwap);
    }

    CoordinateHud(Function<Player, List<RemoteSession>> sessionsFor, HudDisplay display) {
        this(sessionsFor, display, false);
    }

    CoordinateHud(Function<Player, List<RemoteSession>> sessionsFor, HudDisplay display, boolean defaultToggleOnSwap) {
        this.sessionsFor = sessionsFor;
        this.display = display;
        this.defaultToggleOnSwap = defaultToggleOnSwap;
    }

    void tick(Collection<? extends Player> onlinePlayers) {
        Set<UUID> online = new HashSet<>();
        for (Player player : onlinePlayers) {
            online.add(player.getUniqueId());
            refresh(player);
        }
        states.keySet().removeIf(id -> {
            if (online.contains(id)) return false;
            display.hide(id);
            return true;
        });
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new State(defaultToggleOnSwap));
    }

    private void refresh(Player player) {
        State state = state(player);
        List<RemoteSession> active = sessionsFor.apply(player).stream()
                .filter(session -> !session.pendingRemoval && session.isHelloComplete()
                        && player.getUniqueId().equals(session.getBoundUuid())).toList();
        UUID selected = state.selection.reconcile(active.stream().map(RemoteSession::getConnectionEpoch).toList());
        if (!state.visible) {
            display.hide(player.getUniqueId());
            return;
        }
        Location origin = active.stream().filter(session -> session.getConnectionEpoch().equals(selected))
                .findFirst().map(RemoteSession::getOrigin).orElse(null);
        display.update(player, HudSampler.sample(player, origin, state.point));
    }

    void setVisible(Player player, boolean visible) {
        state(player).visible = visible;
        refresh(player);
    }

    boolean isVisible(Player player) {
        State state = states.get(player.getUniqueId());
        return state == null || state.visible;
    }

    void setPoint(Player player, HudSampler.Point point) {
        state(player).point = point;
        refresh(player);
    }

    void togglePoint(Player player) {
        setPoint(player, state(player).point == HudSampler.Point.LEGS
                ? HudSampler.Point.TARGET : HudSampler.Point.LEGS);
    }

    void setToggleOnSwap(Player player, boolean enabled) {
        state(player).toggleOnSwap = enabled;
    }

    boolean isToggleOnSwapEnabled(Player player) {
        State state = states.get(player.getUniqueId());
        return state == null ? defaultToggleOnSwap : state.toggleOnSwap;
    }

    String status(Player player) {
        State state = state(player);
        return "HUD: " + (state.visible ? "ON" : "OFF") + " / "
                + (state.point == HudSampler.Point.LEGS ? "足元の座標" : "照準先のブロック座標")
                + " / toggle-on-swap: " + (state.toggleOnSwap ? "ON" : "OFF");
    }

    void playerQuit(Player player) {
        display.hide(player.getUniqueId());
        states.remove(player.getUniqueId());
    }

    @Override
    public void close() {
        display.close();
        states.clear();
    }
}
