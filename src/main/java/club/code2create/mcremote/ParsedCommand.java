package club.code2create.mcremote;

public class ParsedCommand {
    private final String name;
    private final String[] args;

    public ParsedCommand(String name, String[] args) {
        this.name = name;
        this.args = args;
    }

    public String getName() {
        return name;
    }

    public String[] getArgs() {
        return args;
    }
}
