package club.code2create.mcremote;

public class CommandParser {
    public ParsedCommand parse(String line) {
        String[] parts = line.split("\\(", 2);
        String command = parts[0];
        if (parts.length == 1) {
            return new ParsedCommand(command, new String[0]);
        }

        String rawArgs = parts[1];
        if (!rawArgs.endsWith(")")) {
            throw new IllegalArgumentException("Malformed command: " + line);
        }

        String body = rawArgs.substring(0, rawArgs.length() - 1);
        String[] args = body.isEmpty() ? new String[0] : body.split(",");
        return new ParsedCommand(command, args);
    }
}
