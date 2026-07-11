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
                session.respondError(-32601, "method_not_found", null);
                logger.warning("No such method: " + commandName);
                return;
            }

            if (registration.requiresOrigin() && session.getOrigin() == null) {
                session.respondError(-32000, "origin_not_set", null);
                logger.severe("Build origin is not set. Method: "
                        + commandName + ", Params: " + Arrays.toString(args));
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
