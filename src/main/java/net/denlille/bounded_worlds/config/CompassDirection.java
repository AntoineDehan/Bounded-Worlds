package net.denlille.bounded_worlds.config;

import java.util.Random;

public enum CompassDirection {
    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0),
    RANDOM(0, 0);

    private final int dx;
    private final int dz;

    CompassDirection(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int getDx() {
        return dx;
    }

    public int getDz() {
        return dz;
    }

    public CompassDirection opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            case RANDOM -> RANDOM;
        };
    }

    /**
     * Checks if this direction is perpendicular to another.
     * RANDOM is considered perpendicular to anything (resolved later).
     */
    public boolean isPerpendicularTo(CompassDirection other) {
        if (this == RANDOM || other == RANDOM) return true;
        // N/S are on the Z axis (dx==0), E/W are on the X axis (dz==0)
        return (this.dx == 0) != (other.dx == 0);
    }

    /**
     * Resolves RANDOM to a concrete direction using the given Random.
     * Non-RANDOM directions return themselves.
     */
    public CompassDirection resolve(Random random) {
        if (this != RANDOM) return this;
        CompassDirection[] concrete = {NORTH, SOUTH, EAST, WEST};
        return concrete[random.nextInt(concrete.length)];
    }

    /**
     * Resolves RANDOM to a concrete direction that is perpendicular to the given direction.
     */
    public CompassDirection resolvePerpendicularTo(CompassDirection other, Random random) {
        if (this != RANDOM) return this;
        if (other == RANDOM || other.dx == 0) {
            // Other is N/S or RANDOM → pick E or W
            return random.nextBoolean() ? EAST : WEST;
        } else {
            // Other is E/W → pick N or S
            return random.nextBoolean() ? NORTH : SOUTH;
        }
    }
}
