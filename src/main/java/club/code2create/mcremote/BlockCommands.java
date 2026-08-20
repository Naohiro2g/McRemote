package club.code2create.mcremote;

public class BlockCommands {
    private final BlockQueryCommands queryCommands;
    private final BlockEditCommands editCommands;

    public BlockCommands(RemoteSession session, MiscCommands miscCommands) {
        this.queryCommands = new BlockQueryCommands(session, miscCommands);
        this.editCommands = new BlockEditCommands(session, miscCommands);
    }

    public void register(CommandRegistry registry) {
        registry.registerStructured("world.getBlock", queryCommands::handleGetBlock);
        registry.registerStructured("world.getBlocks", queryCommands::handleGetBlocks);
        registry.registerStructured("world.setBlock", editCommands::handleSetBlock);
        registry.registerStructured("world.setBlocks", editCommands::handleSetBlocks);
    }
}
