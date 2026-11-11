package club.code2create.mcremote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class SqliteCredentialFileModesTest {
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(strings = {"002", "022", "077"})
    void newCredentialBundleIsOwnerOnly(String umask) throws Exception { probe(umask, "create"); }

    @ParameterizedTest @ValueSource(strings = {"002", "022", "077"})
    void existingCredentialBundleIsRestrictedAtStartupAndReopen(String umask) throws Exception {
        probe(umask, "existing");
    }

    private void probe(String umask, String scenario) throws Exception {
        assumeTrue(Files.getFileAttributeView(temp, PosixFileAttributeView.class) != null,
                "POSIX mode cases apply only to POSIX filesystems; native lifecycle is tested separately");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path log = temp.resolve("probe.log");
        Process child = new ProcessBuilder("sh", "-c", "umask \"$1\"; shift; exec \"$@\"",
                "credential-mode-test", umask, java, "-cp", subprocessClasspath(),
                SqliteCredentialFileModesProbe.class.getName(), temp.resolve("runtime").toString(), scenario)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(45, TimeUnit.SECONDS);
        if (!finished) child.destroyForcibly();
        assertTrue(finished, "credential mode probe timed out");
        String output = Files.readString(log);
        System.out.println("umask=" + umask + " scenario=" + scenario + "\n" + output);
        assertEquals(0, child.exitValue(), "umask=" + umask + " scenario=" + scenario + "\n" + output);
        assertTrue(output.contains("PASS update and open sidecars"));
        assertTrue(output.contains("PASS reset"));
        assertTrue(output.contains("PASS recreate and unchanged shared parent"));
    }

    @Test void nativeFilesystemStillSupportsCredentialLifecycle() throws Exception {
        Path snapshot = temp.resolve("native/store/snapshot.json"), authority = temp.resolve("native/authority");
        try (var service = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.HEALTHY, service.health());
            var issued = service.issue(java.util.UUID.randomUUID(), "synthetic");
            assertEquals(CredentialService.ResolveStatus.ACTIVE, service.resolveAndTouch(issued.token()).status());
            service.reset();
            assertEquals(CredentialService.ResolveStatus.NOT_FOUND, service.resolveAndTouch(issued.token()).status());
        }
        try (var restarted = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.HEALTHY, restarted.health());
        }
    }

    @Test void failureToRestrictPermissionsIsNotIgnored() throws Exception {
        var view = mock(PosixFileAttributeView.class);
        var attrs = mock(PosixFileAttributes.class);
        when(view.readAttributes()).thenReturn(attrs);
        when(attrs.permissions()).thenReturn(PosixFilePermissions.fromString("rw-rw-r--"));
        var mode = PosixFilePermissions.fromString("rw-------");
        doThrow(new java.nio.file.AccessDeniedException("synthetic")).when(view).setPermissions(mode);
        var failure = assertThrows(java.io.IOException.class, () -> CredentialDiagnostics.run(
                CredentialDiagnostics.Operation.RESTRICT_FILE_PERMISSIONS,
                () -> CredentialFilePermissions.restrict(view, mode)));
        assertTrue(CredentialDiagnostics.summary(failure).contains("file.restrict-permissions"));
        doThrow(new UnsupportedOperationException("synthetic")).when(view).setPermissions(mode);
        assertThrows(java.io.IOException.class, () -> CredentialFilePermissions.restrict(view, mode));
    }

    @Test void aProviderThatDoesNotApplyPermissionsIsRejected() throws Exception {
        var view = mock(PosixFileAttributeView.class);
        var attrs = mock(PosixFileAttributes.class);
        when(view.readAttributes()).thenReturn(attrs);
        when(attrs.permissions()).thenReturn(PosixFilePermissions.fromString("rw-rw-r--"));
        assertThrows(java.io.IOException.class,
                () -> CredentialFilePermissions.restrict(view, PosixFilePermissions.fromString("rw-------")));
    }

    @Test void symlinkSidecarFailsClosedWithoutChangingItsTarget() throws Exception {
        assumeTrue(Files.getFileAttributeView(temp, PosixFileAttributeView.class) != null);
        Path snapshot = temp.resolve("store/snapshot.json"), authority = temp.resolve("authority");
        try (var initial = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.HEALTHY, initial.health());
        }
        Path target = Files.writeString(temp.resolve("unrelated"), "synthetic");
        var previous = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(target, previous);
        Files.createSymbolicLink(temp.resolve("store/snapshot.json.sqlite-wal"), target);
        try (var restarted = new CredentialService(snapshot, authority, 16)) {
            assertEquals(CredentialService.Health.UNHEALTHY, restarted.health());
        }
        assertEquals(previous, Files.getPosixFilePermissions(target));
        assertEquals("synthetic", Files.readString(target));
    }

    private static String subprocessClasspath() throws Exception {
        List<String> entries = new ArrayList<>();
        for (Class<?> type : List.of(SqliteCredentialFileModesProbe.class, CredentialService.class,
                org.sqlite.JDBC.class, com.google.gson.Gson.class, org.slf4j.LoggerFactory.class)) {
            String entry = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!entries.contains(entry)) entries.add(entry);
        }
        return String.join(java.io.File.pathSeparator, entries);
    }
}
