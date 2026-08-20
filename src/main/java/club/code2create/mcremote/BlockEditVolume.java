package club.code2create.mcremote;

/** Pure inclusive-volume calculation used before any setBlocks world access. */
final class BlockEditVolume {
    private BlockEditVolume() {
    }

    static long between(int x1, int y1, int z1, int x2, int y2, int z2) {
        long xLength = Math.abs((long) x2 - x1) + 1L;
        long yLength = Math.abs((long) y2 - y1) + 1L;
        long zLength = Math.abs((long) z2 - z1) + 1L;
        return Math.multiplyExact(Math.multiplyExact(xLength, yLength), zLength);
    }
}
