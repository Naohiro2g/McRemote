package club.code2create.mcremote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Isolated local pulse credential, issued via the real service before server startup. */
public final class LocalCompatibilityFixture {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        var service = new CredentialService(data.resolve("credential-store/snapshot.json"),
                data.resolve("credential-revocations"), 16);
        String token = service.issue(UUID.randomUUID(), "b10-local-compatibility").token();
        Files.writeString(Path.of(args[1]), token);
    }
}
