package net.denlille.bounded_worlds.biome;

import java.util.Random;

public enum BiomeZoneSize {
    SMALL(100, 200),
    MEDIUM(250, 350),
    LARGE(400, 600);

    private final int minSize;
    private final int maxSize;

    BiomeZoneSize(int minSize, int maxSize) {
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    public int getMinSize() {
        return minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Returns a random size within this category's range.
     */
    public int randomSize(Random random) {
        return minSize + random.nextInt(maxSize - minSize + 1);
    }
}
