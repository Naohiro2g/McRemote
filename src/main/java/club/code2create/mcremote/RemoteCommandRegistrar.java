package club.code2create.mcremote;

public class RemoteCommandRegistrar {
    public CommandRegistry createRegistry(
            RemoteSession session,
            BlockCommands blockCommands,
            MiscCommands miscCommands,
            EntityCommands entityCommands,
            BuildStateCommands buildStateCommands,
            CatalogCommands catalogCommands,
            EventCommands eventCommands,
            WorldB5Commands worldB5Commands,
            SignCommands signCommands
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
        registry.register("world.getNearbyEntities",
                args -> entityCommands.handleGetNearbyEntities(session.getOrigin().getWorld(), args));
        registry.register("entity.getPos", args -> entityCommands.handleEntityCommands("entity.getPos", args));
        registry.register("entity.setPos", args -> entityCommands.handleEntityCommands("entity.setPos", args));
        registry.register("entity.getRotation", args -> entityCommands.handleEntityCommands("entity.getRotation", args));
        registry.register("entity.setRotation", args -> entityCommands.handleEntityCommands("entity.setRotation", args));
        registry.register("entity.getPitch", args -> entityCommands.handleEntityCommands("entity.getPitch", args));
        registry.register("entity.setPitch", args -> entityCommands.handleEntityCommands("entity.setPitch", args));
        registry.register("entity.getYaw", args -> entityCommands.handleEntityCommands("entity.getYaw", args));
        registry.register("entity.setYaw", args -> entityCommands.handleEntityCommands("entity.setYaw", args));
        registry.register("entity.remove", args -> entityCommands.handleEntityCommands("entity.remove", args));
        registry.registerStructured("player.getPos", playerCommands::handleGetPosStructured);
        registry.registerStructured("player.setPos", playerCommands::handleSetPosStructured);
        registry.registerStructured("player.getPose", playerCommands::handleGetPoseStructured);
        registry.registerStructured("player.setPose", playerCommands::handleSetPoseStructured);
        // b6 candidate wire contract; exact shape pending knowledge-repo ratification (DECISIONS 2026-08-16-06).
        registry.registerStructured("world.setSign", signCommands::handleSetSign);
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
}
