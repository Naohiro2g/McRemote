package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CredentialDiagnosticsTest {
    @TempDir
    Path temp;

    @Test
    void bootstrapFailureLogsOperationAndCauseTypesWithoutPrivateValues() {
        String secret = "mcrl_do_not_log_this_token";
        String privatePath = "C:\\Users\\private-owner\\" + secret;
        IOException failure = new IOException("token_hash=private-hash device=" + secret,
                new AccessDeniedException(privatePath));
        RevocationAuthority authority = new RevocationAuthority(temp.resolve("authority")) {
            @Override
            UUID beginBootstrap() throws IOException {
                throw new CredentialDiagnostics.Failure(
                        CredentialDiagnostics.Operation.OPEN_DIRECTORY, failure);
            }
        };

        try (CapturedLogs logs = new CapturedLogs()) {
            CredentialService service = new CredentialService(
                    new CredentialStore(temp.resolve("snapshot.json")), authority, 16);

            assertEquals(CredentialService.Health.UNHEALTHY, service.health());
            assertNull(service.credentialDomainId());
            String text = logs.text() + service.healthDetail();
            assertTrue(text.contains("authority.begin-bootstrap"));
            assertTrue(text.contains("directory.open-read"));
            assertTrue(text.contains(IOException.class.getName()));
            assertTrue(text.contains(AccessDeniedException.class.getName()));
            assertFalse(text.contains(secret));
            assertFalse(text.contains(privatePath));
            assertFalse(text.contains("private-hash"));
            assertFalse(text.contains(temp.toString()));
            assertTrue(logs.records.stream().allMatch(record -> record.getThrown() == null));
        }
    }

    @Test
    void survivingSnapshotReadFailureRetainsContextAndFailsClosed() {
        CredentialStore store = new CredentialStore(temp.resolve("snapshot.json")) {
            @Override
            LoadedSnapshot load() throws IOException {
                throw new IOException("private token=mcrs_hidden", new IllegalArgumentException("private UUID"));
            }
        };
        assertDoesNotThrow(() -> java.nio.file.Files.writeString(store.path(), "{}"));
        try (CapturedLogs logs = new CapturedLogs()) {
            CredentialService service = new CredentialService(store,
                    new RevocationAuthority(temp.resolve("authority")), 16);
            assertEquals(CredentialService.Health.UNHEALTHY, service.health());
            assertTrue(logs.text().contains("snapshot.read"));
            assertTrue(logs.text().contains(IllegalArgumentException.class.getName()));
            assertFalse(logs.text().contains("mcrs_hidden"));
            assertFalse(logs.text().contains("private UUID"));
        }
    }

    @Test
    void healthyInitializationLogsRetirementPresenceWithoutPathsOrDomainValues() throws Exception {
        Path snapshot = temp.resolve("operator-private").resolve("snapshot.json");
        Path authority = temp.resolve("operator-private-authority");
        CredentialService initial = new CredentialService(snapshot, authority, 16);
        java.nio.file.Files.delete(snapshot.resolveSibling("snapshot.json.sqlite"));
        try (CapturedLogs logs = new CapturedLogs()) {
            CredentialService recovered = new CredentialService(snapshot, authority, 16);
            assertEquals(CredentialService.Health.HEALTHY, recovered.health());
            String text = logs.text();
            assertTrue(text.contains("retired_authority=retained"));
            assertTrue(text.contains("retired_snapshot=none"));
            assertFalse(text.contains(temp.toString()));
            assertFalse(text.contains(initial.credentialDomainId().toString()));
            assertFalse(text.contains(recovered.credentialDomainId().toString()));
        }
    }

    @Test
    void directoryOpenFailureIdentifiesOpenWithoutSwallowingIOException() {
        IOException failure = assertThrows(IOException.class,
                () -> CredentialStore.forceDirectory(temp.resolve("missing-private-directory")));
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
        String text = CredentialDiagnostics.summary(failure);
        assertTrue(text.contains("directory.open-read"));
        assertTrue(text.contains(NoSuchFileException.class.getName()));
        assertFalse(text.contains("missing-private-directory"));
        assertFalse(text.contains(temp.toString()));
    }

    @Test
    void cyclicCausesDoNotLoopOrExposeMessages() {
        IOException first = new IOException("private-first");
        IOException second = new IOException("private-second");
        first.initCause(second);
        second.initCause(first);
        String text = CredentialDiagnostics.summary(first);
        assertTrue(text.contains("cycle-or-depth-limit"));
        assertFalse(text.contains("private-first"));
        assertFalse(text.contains("private-second"));
    }

    private static final class CapturedLogs extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger("McR_CredentialService");
        private final boolean parentHandlers = logger.getUseParentHandlers();
        private final List<LogRecord> records = new ArrayList<>();

        CapturedLogs() {
            logger.setUseParentHandlers(false);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        String text() {
            return records.stream().map(LogRecord::getMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setUseParentHandlers(parentHandlers);
        }
    }
}
