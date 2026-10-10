package club.code2create.mcremote;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;

/** credential の値や filesystem path を含まない、操作と例外型だけの診断。 */
final class CredentialDiagnostics {
    enum Operation {
        VALIDATE_PATHS("backend.validate-paths"),
        CHECK_SNAPSHOT("snapshot.check-existence"),
        CHECK_MANIFEST("authority.check-manifest-existence"),
        READ_SNAPSHOT("snapshot.read"),
        READ_MANIFEST("authority.read-manifest"),
        READ_TOMBSTONES("authority.read-tombstones"),
        LOAD_STATE("backend.load-state"),
        BOOTSTRAP("backend.bootstrap"),
        ARCHIVE_AUTHORITY("authority.archive"),
        ARCHIVE_SNAPSHOT("snapshot.archive"),
        BEGIN_BOOTSTRAP("authority.begin-bootstrap"),
        INITIALIZE_SNAPSHOT("snapshot.initialize"),
        COMPLETE_BOOTSTRAP("authority.complete-bootstrap"),
        CREATE_DIRECTORY("directory.create"),
        OPEN_FILE("file.open-new"),
        WRITE_FILE("file.write"),
        SYNC_FILE("file.sync"),
        CLOSE_FILE("file.close"),
        PUBLISH_SNAPSHOT("snapshot.atomic-publish"),
        OPEN_DIRECTORY("directory.open-read"),
        SYNC_DIRECTORY("directory.sync"),
        CLOSE_DIRECTORY("directory.close"),
        OPEN_DATABASE("sqlite.open"),
        SQLITE_PROVIDER("sqlite.driver-provider"),
        CONFIGURE_DATABASE("sqlite.configure-and-readback"),
        VALIDATE_DATABASE("sqlite.validate"),
        READ_DATABASE("sqlite.read"),
        BEGIN_DATABASE_WRITE("sqlite.begin-immediate"),
        WRITE_DATABASE("sqlite.write"),
        COMMIT_DATABASE("sqlite.commit"),
        CLOSE_DATABASE("sqlite.close");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @FunctionalInterface
    interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    static final class Failure extends IOException {
        private final Operation operation;

        Failure(Operation operation, IOException cause) {
            super("Credential operation failed: " + operation.label(), cause);
            this.operation = operation;
        }
    }

    private CredentialDiagnostics() {}

    static <T> T perform(Operation operation, IoSupplier<T> action) throws IOException {
        try {
            return action.get();
        } catch (IOException cause) {
            throw new Failure(operation, cause);
        }
    }

    static void run(Operation operation, IoAction action) throws IOException {
        perform(operation, () -> {
            action.run();
            return null;
        });
    }

    static String summary(Throwable cause) {
        StringJoiner operations = new StringJoiner(" -> ");
        StringJoiner types = new StringJoiner(" -> ");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = cause;
        int depth = 0;
        while (current != null && visited.add(current) && depth++ < 16) {
            if (current instanceof Failure failure) {
                operations.add(failure.operation.label());
            }
            // message、toString、stack trace、Throwable 付き Logger 呼び出しは使わない。
            types.add(current.getClass().getName());
            current = current.getCause();
        }
        if (current != null) {
            types.add("[cycle-or-depth-limit]");
        }
        return "operations=[" + operations + "]; cause_chain=[" + types + "]";
    }
}
