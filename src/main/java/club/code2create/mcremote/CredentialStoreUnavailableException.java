package club.code2create.mcremote;

/**
 * long-lived credential backend の信頼性または durable な操作結果を確定できない。
 * wire では {@code credential_store_unavailable} と {@code data.operation} に写像する。
 */
public class CredentialStoreUnavailableException extends Exception {
    public enum Operation {
        RESOLVE("resolve"),
        ISSUE("issue"),
        LIST("list"),
        REVOKE("revoke"),
        TOUCH("touch");

        private final String wireName;

        Operation(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    private final Operation operation;

    public CredentialStoreUnavailableException(Operation operation, String message) {
        super(message);
        this.operation = operation;
    }

    public CredentialStoreUnavailableException(Operation operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
    }

    public Operation operation() {
        return operation;
    }
}
