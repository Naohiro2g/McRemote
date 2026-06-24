package club.code2create.mcremote;

@FunctionalInterface
public interface RemoteCommand {
    void execute(String[] args);
}
