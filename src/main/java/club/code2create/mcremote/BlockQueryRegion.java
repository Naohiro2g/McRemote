package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** Pure protocol-22 bounds validation and deterministic traversal for world.getBlocks. */
final class BlockQueryRegion {
    static final int MAX_AXIS_LENGTH = 10;
    static final int MAX_BLOCKS = 1_000;

    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;

    private BlockQueryRegion(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    static BlockQueryRegion parse(JsonElement params) throws WorkLimitExceededException {
        JsonArray args = WireParams.positional(params, 6);
        return between(
                WireParams.integer(args, 0),
                WireParams.integer(args, 1),
                WireParams.integer(args, 2),
                WireParams.integer(args, 3),
                WireParams.integer(args, 4),
                WireParams.integer(args, 5));
    }

    static BlockQueryRegion between(int x1, int y1, int z1, int x2, int y2, int z2)
            throws WorkLimitExceededException {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        long xLength = inclusiveLength(minX, maxX);
        long yLength = inclusiveLength(minY, maxY);
        long zLength = inclusiveLength(minZ, maxZ);
        if (xLength > MAX_AXIS_LENGTH || yLength > MAX_AXIS_LENGTH || zLength > MAX_AXIS_LENGTH) {
            throw new WorkLimitExceededException();
        }
        if (xLength * yLength * zLength > MAX_BLOCKS) {
            throw new WorkLimitExceededException();
        }
        return new BlockQueryRegion(minX, maxX, minY, maxY, minZ, maxZ);
    }

    BlockQueryRegion translate(int x, int y, int z) {
        try {
            return new BlockQueryRegion(
                    Math.addExact(minX, x), Math.addExact(maxX, x),
                    Math.addExact(minY, y), Math.addExact(maxY, y),
                    Math.addExact(minZ, z), Math.addExact(maxZ, z));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("coordinate plus build origin is outside integer range", e);
        }
    }

    int size() {
        return Math.multiplyExact(
                Math.multiplyExact(axisLength(minX, maxX), axisLength(minY, maxY)),
                axisLength(minZ, maxZ));
    }

    List<Position> positions() {
        int xLength = axisLength(minX, maxX);
        int yLength = axisLength(minY, maxY);
        int zLength = axisLength(minZ, maxZ);
        List<Position> positions = new ArrayList<>(size());
        for (int xOffset = 0; xOffset < xLength; xOffset++) {
            int x = minX + xOffset;
            for (int yOffset = 0; yOffset < yLength; yOffset++) {
                int y = minY + yOffset;
                for (int zOffset = 0; zOffset < zLength; zOffset++) {
                    positions.add(new Position(x, y, minZ + zOffset));
                }
            }
        }
        return List.copyOf(positions);
    }

    private static long inclusiveLength(int min, int max) {
        return (long) max - min + 1L;
    }

    private static int axisLength(int min, int max) {
        return Math.toIntExact(inclusiveLength(min, max));
    }

    record Position(int x, int y, int z) {
    }

    static final class WorkLimitExceededException extends Exception {
    }
}
