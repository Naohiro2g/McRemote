package club.code2create.mcremote;

import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class CommandRegistry {
    private final Map<String, CommandRegistration> commands = new HashMap<>();

    public void register(String name, RemoteCommand command) {
        register(name, command, true);
    }

    public void register(String name, RemoteCommand command, boolean requiresOrigin) {
        commands.put(name, new CommandRegistration(command, null, requiresOrigin));
    }

    public void registerStructured(String name, StructuredRemoteCommand command) {
        registerStructured(name, command, true);
    }

    public void registerStructured(String name, StructuredRemoteCommand command, boolean requiresOrigin) {
        commands.put(name, new CommandRegistration(null, command, requiresOrigin));
    }

    public CommandRegistration get(String name) {
        return commands.get(name);
    }

    public Set<String> names() {
        return commands.keySet();
    }

    public static class CommandRegistration {
        private final RemoteCommand command;
        private final StructuredRemoteCommand structuredCommand;
        private final boolean requiresOrigin;

        private CommandRegistration(
                RemoteCommand command,
                StructuredRemoteCommand structuredCommand,
                boolean requiresOrigin
        ) {
            this.command = command;
            this.structuredCommand = structuredCommand;
            this.requiresOrigin = requiresOrigin;
        }

        public void execute(ParsedCommand parsedCommand) {
            if (structuredCommand != null) {
                JsonElement params = parsedCommand.getParams();
                structuredCommand.execute(params);
            } else {
                command.execute(parsedCommand.getArgs());
            }
        }

        public boolean requiresOrigin() {
            return requiresOrigin;
        }
    }
}
