package club.code2create.mcremote;

import java.util.UUID;

/** Minimal session surface needed by the protocol 23.1 command handlers. */
interface B7CommandContext extends WorldCommandContext {
    UUID getBoundUuid();

    UUID getConnectionEpoch();

    boolean admitSetterWork(long units);

    boolean rejectTemporaryBackpressure();

}
