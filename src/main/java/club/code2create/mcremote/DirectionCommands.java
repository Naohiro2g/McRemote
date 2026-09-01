package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Function;

/** Protocol 23.1 player/entity direction four-method slice. */
final class DirectionCommands {
    private final B7CommandContext session;
    private final EntityHandleRegistry handles;
    private final Function<UUID, Player> onlinePlayers;

    DirectionCommands(B7CommandContext session, EntityHandleRegistry handles) {
        this(session, handles, Bukkit::getPlayer);
    }

    DirectionCommands(
            B7CommandContext session,
            EntityHandleRegistry handles,
            Function<UUID, Player> onlinePlayers
    ) {
        this.session = session;
        this.handles = handles;
        this.onlinePlayers = onlinePlayers;
    }

    void register(CommandRegistry registry) {
        registry.registerStructured("player.getDirection", this::handlePlayerGet, false);
        registry.registerStructured("player.setDirection", this::handlePlayerSet, false);
        registry.registerStructured("entity.getDirection", this::handleEntityGet, false);
        registry.registerStructured("entity.setDirection", this::handleEntitySet, false);
    }

    void handlePlayerGet(JsonElement params) {
        try {
            WireParams.positional(params, 0);
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        Player player = requireOnlinePlayer();
        if (player == null) {
            return;
        }
        respondDirection(player);
    }

    void handlePlayerSet(JsonElement params) {
        DirectionValue.Unit direction;
        try {
            direction = DirectionValue.parse(WireParams.positional(params, 3), 0);
        } catch (DirectionValue.ZeroDirectionException e) {
            session.respondError(-32602, "zero_direction", null);
            return;
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        Player player = requireOnlinePlayer();
        if (player == null || !session.admitSetterWork(1)) {
            return;
        }
        setAndRespond(player, direction);
    }

    void handleEntityGet(JsonElement params) {
        String handle;
        try {
            JsonArray args = WireParams.positional(params, 1);
            handle = WireParams.string(args, 0);
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        if (!requireEntityPermission()) {
            return;
        }
        Entity entity = resolve(handle);
        if (entity != null) {
            respondDirection(entity);
        }
    }

    void handleEntitySet(JsonElement params) {
        String handle;
        DirectionValue.Unit direction;
        try {
            JsonArray args = WireParams.positional(params, 4);
            handle = WireParams.string(args, 0);
            direction = DirectionValue.parse(args, 1);
        } catch (DirectionValue.ZeroDirectionException e) {
            session.respondError(-32602, "zero_direction", null);
            return;
        } catch (IllegalArgumentException e) {
            invalidParams();
            return;
        }
        if (!requireEntityPermission()) {
            return;
        }
        Entity entity = resolve(handle);
        if (entity == null || !session.admitSetterWork(1)) {
            return;
        }
        setAndRespond(entity, direction);
    }

    private Player requireOnlinePlayer() {
        UUID playerId = session.getBoundUuid();
        if (playerId == null) {
            session.respondError(-32000, "auth_required", null);
            return null;
        }
        Player player = onlinePlayers.apply(playerId);
        if (player == null || !player.isOnline()) {
            session.respondError(-32000, "player_offline", null);
            return null;
        }
        if (!session.hasConstructionPermission()) {
            session.respondError(-32000, "permission_denied", null);
            return null;
        }
        return player;
    }

    private boolean requireEntityPermission() {
        if (session.hasConstructionPermission()) {
            return true;
        }
        session.respondError(-32000, "permission_denied", null);
        return false;
    }

    private Entity resolve(String handle) {
        EntityHandleRegistry.ResolveResult resolved = handles.resolve(handle);
        return switch (resolved.status()) {
            case ACTIVE -> resolved.entity();
            case NOT_FOUND -> {
                session.respondError(-32000, "entity_not_found", null);
                yield null;
            }
            case REMOVED_OR_UNLOADED -> {
                session.respondError(-32000, "entity_unavailable", null);
                yield null;
            }
            case DIMENSION_CHANGED -> {
                session.respondError(-32000, "entity_dimension_changed", null);
                yield null;
            }
        };
    }

    private void setAndRespond(Entity entity, DirectionValue.Unit direction) {
        try {
            entity.setRotation(direction.yaw(), direction.pitch());
            session.respondResult(DirectionValue.read(entity).wire());
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    private void respondDirection(Entity entity) {
        try {
            session.respondResult(DirectionValue.read(entity).wire());
        } catch (Exception e) {
            session.respondError(-32000, "internal_error", null);
        }
    }

    private void invalidParams() {
        session.respondError(-32602, "invalid_params", null);
    }
}
