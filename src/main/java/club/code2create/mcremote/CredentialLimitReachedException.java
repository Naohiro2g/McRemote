package club.code2create.mcremote;

/** UUID ごとの active long-lived credential 上限到達。 */
public class CredentialLimitReachedException extends Exception {
    private final int limit;
    private final int active;

    public CredentialLimitReachedException(int limit, int active) {
        super("Active long-lived credential limit reached: " + active + "/" + limit);
        this.limit = limit;
        this.active = active;
    }

    public int limit() {
        return limit;
    }

    public int active() {
        return active;
    }
}
