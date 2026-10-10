package club.code2create.mcremote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.jar.JarFile;

/** Release JAR beside Paper's dependency fixture, or without a JDBC provider. */
public final class SqlitePackagingProbe {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(CredentialService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile contents = new JarFile(jar.toFile())) {
            if (contents.stream().anyMatch(entry -> entry.getName().contains("org/sqlite/"))) {
                throw new AssertionError("SQLite classes/native must not be embedded");
            }
        }
        Path root = Files.createTempDirectory("mcremote-sqlite-packaging-");
        try {
            Path snapshot = root.resolve("snapshot.json"), authority = root.resolve("authority");
            try (CredentialService service = new CredentialService(snapshot, authority, 16)) {
                if (args.length != 0 && args[0].equals("--missing-provider")) {
                    if (service.health() != CredentialService.Health.UNHEALTHY
                            || !service.healthDetail().contains("sqlite.driver-provider")
                            || !service.healthDetail().contains("ClassNotFoundException")) {
                        throw new AssertionError("missing provider must fail closed with cause identity");
                    }
                    try { service.issueSession(UUID.randomUUID(), null, 7200); }
                    catch (CredentialStoreUnavailableException expected) {
                        System.out.println("missing_provider=FAIL_CLOSED status=PASS"); return;
                    }
                    throw new AssertionError("missing provider authenticated a client");
                }
                if (service.health() != CredentialService.Health.HEALTHY) {
                    throw new AssertionError("Paper SQLite failed: " + service.healthDetail());
                }
                Class<?> jdbc = Class.forName("org.sqlite.JDBC");
                Path provider = Path.of(jdbc.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (provider.equals(jar) || !provider.getFileName().toString().equals("sqlite-jdbc-3.49.1.0.jar")) {
                    throw new AssertionError("driver is not the Paper dependency fixture");
                }
                UUID player = UUID.randomUUID();
                var issued = service.issue(player, "packaging probe");
                service.resolveAndTouch(issued.token()); service.revoke(player, issued.credentialId());
                try (CredentialService restarted = new CredentialService(snapshot, authority, 16)) {
                    if (restarted.resolveAndTouch(issued.token()).status() != CredentialService.ResolveStatus.REVOKED) {
                        throw new AssertionError("durable revoke did not survive restart");
                    }
                }
                System.out.println("provider_source=" + provider.getFileName());
                System.out.println("embedded_sqlite_entries=0 status=PASS");
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
