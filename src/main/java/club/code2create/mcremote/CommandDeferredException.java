package club.code2create.mcremote;

/** Internal control signal: retain the current FIFO head and retry it on a later tick. */
final class CommandDeferredException extends RuntimeException {
    static final CommandDeferredException INSTANCE = new CommandDeferredException();

    private CommandDeferredException() {
        super(null, null, false, false);
    }
}
