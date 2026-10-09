package club.code2create.mcremote;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** process crashを再現する子JVM。電源断の耐性を主張する試験ではない。 */
public final class SqliteCredentialCrashProbe {
    public static void main(String[] args) throws Exception {
        String stage = args[1];
        var observer = new SqliteCredentialDatabase.CommitObserver() {
            @Override public void beforeCommit() {
                if (stage.equals("before")) Runtime.getRuntime().halt(71);
            }
            @Override public void afterCommit() {
                if (stage.equals("after")) Runtime.getRuntime().halt(72);
            }
        };
        var authority = new SqliteRevocationAuthority(Path.of(args[0]), observer);
        authority.commit(new RevocationAuthority.Tombstone(UUID.fromString(args[2]),
                UUID.fromString(args[3]), args[4], UUID.fromString(args[5]),
                Instant.parse("2026-10-09T00:00:00Z")));
        throw new IllegalStateException("commit observer did not halt process");
    }
}
