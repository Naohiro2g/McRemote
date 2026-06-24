package club.code2create.mcremote;

public class RemoteCommandRegistrar {
    public CommandRegistry createRegistry(
            RemoteSession session,
            PlayerCommands playerCommands,
            BlockCommands blockCommands,
            MiscCommands miscCommands,
            EntityCommands entityCommands
    ) {
        CommandRegistry registry = new CommandRegistry();

        blockCommands.register(registry);
        registry.register("world.spawnParticle", miscCommands::handleSpawnParticle);
        registry.register("world.getHeight", args -> miscCommands.handleGetHeight(session.getOrigin().getWorld(), args));
        registry.register("chat.post", miscCommands::handleChatPost);
        registry.register("world.spawnEntity", miscCommands::handleSpawnEntity);
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
        registry.register("setPlayer", playerCommands::handleSetPlayerCommand, false);

        return registry;
    }
}
