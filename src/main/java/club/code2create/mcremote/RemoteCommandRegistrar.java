package club.code2create.mcremote;

public class RemoteCommandRegistrar {
    public CommandRegistry createRegistry(
            RemoteSession session,
            BlockCommands blockCommands,
            MiscCommands miscCommands,
            BuildStateCommands buildStateCommands,
            CatalogCommands catalogCommands,
            EventCommands eventCommands,
            WorldB5Commands worldB5Commands,
            SignCommands signCommands,
            DirectionCommands directionCommands,
            LightningCommands lightningCommands
    ) {
        CommandRegistry registry = new CommandRegistry();
        PlayerCommands playerCommands = session.getPlayerCommands();
        ConnectionCommands connectionCommands = new ConnectionCommands(session);

        blockCommands.register(registry);
        registry.registerStructured("connection.flush", connectionCommands::handleFlush, false);
        registry.registerStructured("world.spawnParticle", worldB5Commands::handleSpawnParticle);
        registry.registerStructured("world.getHeight", worldB5Commands::handleGetHeight);
        registry.register("chat.post", miscCommands::handleChatPost, false); // origin 不要・既定 send-only
        registry.registerStructured("world.spawnEntity", worldB5Commands::handleSpawnEntity);
        registerB5EventCommands(registry, eventCommands);
        registry.registerStructured("player.getPos", playerCommands::handleGetPosStructured);
        registry.registerStructured("player.setPos", playerCommands::handleSetPosStructured);
        registry.registerStructured("player.getPose", playerCommands::handleGetPoseStructured);
        registry.registerStructured("player.setPose", playerCommands::handleSetPoseStructured);
        // b6 sign three-op slice; exact wire contract locked by DECISIONS 2026-08-26-05.
        registry.registerStructured("world.setSign", signCommands::handleSetSign);
        registry.registerStructured("world.getSign", signCommands::handleGetSign);
        registry.registerStructured("world.updateSignLine", signCommands::handleUpdateSignLine);
        registerB7Commands(registry, directionCommands, lightningCommands);
        registry.register("catalog.get", catalogCommands::handleGet, false);

        // Protocol 22 build context. build.setWorld is intentionally not registered.
        registerBuildCommands(registry, buildStateCommands);

        return registry;
    }

    static void registerB5EventCommands(CommandRegistry registry, EventCommands eventCommands) {
        registry.registerStructured("events.poll", eventCommands::handlePoll, false);
        // events.clear and filtered polling remain b6-only and intentionally unregistered in b5.
    }

    static void registerBuildCommands(CommandRegistry registry, BuildStateCommands buildStateCommands) {
        registry.registerStructured("build.setDimension", buildStateCommands::handleSetDimension, false);
        registry.registerStructured("build.setOrigin", buildStateCommands::handleSetOrigin, false);
    }

    static void registerB7Commands(
            CommandRegistry registry,
            DirectionCommands directionCommands,
            LightningCommands lightningCommands
    ) {
        directionCommands.register(registry);
        lightningCommands.register(registry);
    }
}
