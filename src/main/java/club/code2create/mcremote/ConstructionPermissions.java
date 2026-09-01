package club.code2create.mcremote;

/** Immutable construction admission resolved once while an authenticated hello is admitted. */
record ConstructionPermissions(boolean onlineAllowed, boolean offlineAllowed, int buildRange) {
    ConstructionPermissions {
        if (buildRange < 0) {
            throw new IllegalArgumentException("build range must be non-negative");
        }
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
