package club.code2create.mcremote;

/** Immutable construction admission resolved once while an authenticated hello is admitted. */
record ConstructionPermissions(boolean onlineAllowed, boolean offlineAllowed, int buildRange, int buildBlocks) {
    static final int DEFAULT_BUILD_BLOCKS = 4_096;

    ConstructionPermissions(boolean onlineAllowed, boolean offlineAllowed, int buildRange) {
        this(onlineAllowed, offlineAllowed, buildRange, DEFAULT_BUILD_BLOCKS);
    }

    ConstructionPermissions {
        if (buildRange < 0) {
            throw new IllegalArgumentException("build range must be non-negative");
        }
        if (buildBlocks < 0) {
            throw new IllegalArgumentException("build blocks must be non-negative");
        }
    }

    boolean allowsBlockCount(long count) {
        return count >= 1 && count <= buildBlocks;
    }

    boolean allows(boolean playerOnline) {
        return playerOnline ? onlineAllowed : offlineAllowed;
    }

    boolean closesOnQuit() {
        return onlineAllowed && !offlineAllowed;
    }

    boolean closesOnJoin() {
        return !onlineAllowed && offlineAllowed;
    }
}
