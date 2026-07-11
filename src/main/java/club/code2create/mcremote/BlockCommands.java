package club.code2create.mcremote;

public class BlockCommands {
    private final BlockQueryCommands queryCommands;
    private final BlockEditCommands editCommands;

    public BlockCommands(RemoteSession session, MiscCommands miscCommands) {
        this.queryCommands = new BlockQueryCommands(session, miscCommands);
        this.editCommands = new BlockEditCommands(session, miscCommands);
    }

    public void register(CommandRegistry registry) {
        registry.register("world.getBlock", queryCommands::handleGetBlock);
        registry.register("world.getBlocks", queryCommands::handleGetBlocks);
        registry.register("world.getBlockWithData", queryCommands::handleGetBlockWithData);
        registry.register("world.setBlock", editCommands::handleSetBlock);
        registry.register("world.setBlocks", editCommands::handleSetBlocks);
    }
}
