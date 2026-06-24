package club.code2create.mcremote;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.logging.Logger;

public class CommandDispatcher {
    private static final Logger logger = Logger.getLogger("McR_CommandDispatcher");

    private final RemoteSession session;
    private final CommandRegistry registry;

    public CommandDispatcher(RemoteSession session, CommandRegistry registry) {
        this.session = session;
        this.registry = registry;
    }

    public void dispatch(ParsedCommand parsedCommand) {
        String commandName = parsedCommand.getName();
        String[] args = parsedCommand.getArgs();

        try {
            CommandRegistry.CommandRegistration registration = registry.get(commandName);
            if (registration == null) {
                session.send("Error: No such command: " + commandName);
                logger.warning("No such command: " + commandName);
                return;
            }

            if (registration.requiresOrigin() && session.getOrigin() == null) {
                session.send("Error: Player and its origin are not set, please use setPlayer() first.");
                logger.severe("Player and its origin are not set. Command: "
                        + commandName + ", Arguments: " + Arrays.toString(args));
                session.close();
                return;
            }

            registration.getCommand().execute(args);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
            session.close();
        }
    }
}
