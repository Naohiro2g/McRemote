package club.code2create.mcremote;

import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {
    private final Map<String, CommandRegistration> commands = new HashMap<>();

    public void register(String name, RemoteCommand command) {
        register(name, command, true);
    }

    public void register(String name, RemoteCommand command, boolean requiresOrigin) {
        commands.put(name, new CommandRegistration(command, requiresOrigin));
    }

    public CommandRegistration get(String name) {
        return commands.get(name);
    }

    public static class CommandRegistration {
        private final RemoteCommand command;
        private final boolean requiresOrigin;

        private CommandRegistration(RemoteCommand command, boolean requiresOrigin) {
            this.command = command;
            this.requiresOrigin = requiresOrigin;
        }

        public RemoteCommand getCommand() {
            return command;
        }

        public boolean requiresOrigin() {
            return requiresOrigin;
        }
    }
}
