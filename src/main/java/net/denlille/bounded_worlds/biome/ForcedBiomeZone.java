package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Represents a forced biome zone with organic circular shape.
 * Immutable — safe to read from worldgen threads.
 */
public final class ForcedBiomeZone {

    // Noise distorts the circular boundary by ±20% of the radius
    private static final double NOISE_AMPLITUDE = 0.2;
    // Transition zone width: up to 16 blocks of soft blending at edges
    private static final double MAX_TRANSITION_WIDTH = 16.0;
    // Transition zone as fraction of radius (used if radius is small)
    private static final double TRANSITION_FRACTION = 0.15;

    // Core zone data
    private final int centerX;
    private final int centerZ;
    private final int size;
    private final Holder<Biome> biome;
    private final String description;

    // S1: Pre-computed derived values for hot-path performance
    private final double radius;
    private final double transitionWidth;
    private final double maxPossibleRadiusSq;
    private final double minPossibleRadiusSq;
    // I4: Zone-specific noise offsets for visual variety between zones
    private final int noiseOffsetX;
    private final int noiseOffsetZ;

    public ForcedBiomeZone(int centerX, int centerZ, int size, Holder<Biome> biome, String description) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = size;
        this.biome = biome;
        this.description = description;

        // Pre-compute
        this.radius = size / 2.0;
        this.transitionWidth = Math.min(MAX_TRANSITION_WIDTH, radius * TRANSITION_FRACTION);

        // I1: Pre-compute squared radii for fast reject/accept without sqrt
        double maxR = radius * (1.0 + NOISE_AMPLITUDE) + MAX_TRANSITION_WIDTH;
        this.maxPossibleRadiusSq = maxR * maxR;

        // I2: Guard against negative minPossibleRadius
        double minR = radius * (1.0 - NOISE_AMPLITUDE) - MAX_TRANSITION_WIDTH;
        this.minPossibleRadiusSq = minR > 0 ? minR * minR : -1; // -1 means skip quick-accept

        // I4: Derive noise offsets from zone center for unique shapes per zone
        this.noiseOffsetX = centerX * 7 + centerZ * 13;
        this.noiseOffsetZ = centerZ * 7 + centerX * 17;
    }

    // Accessors
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int size() { return size; }
    public Holder<Biome> biome() { return biome; }
    public String description() { return description; }

    /**
     * Checks if a block position falls within this zone.
     * Uses a circular base shape + noise distortion + probabilistic transition at edges.
     */
    public boolean contains(int blockX, int blockZ) {
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;

        // I1: Compare squared distances first — avoids sqrt in ~95% of calls
        double distSq = dx * dx + dz * dz;

        // Quick reject
        if (distSq > maxPossibleRadiusSq) return false;

        // Quick accept (I2: only if minPossibleRadius was positive)
        if (minPossibleRadiusSq > 0 && distSq < minPossibleRadiusSq) return true;

        // Now compute sqrt only for the borderline cases
        double distance = Math.sqrt(distSq);

        // I4: Noise uses zone-specific offsets for unique shapes
        double noiseValue = sampleNoise(blockX + noiseOffsetX, blockZ + noiseOffsetZ, radius);
        double effectiveRadius = radius + noiseValue * radius * NOISE_AMPLITUDE;

        // Transition zone for soft blending
        double innerEdge = effectiveRadius - transitionWidth;

        // Inside the solid core
        if (distance <= innerEdge) return true;
        // Outside the outer edge
        if (distance >= effectiveRadius) return false;

        // In the transition zone: probability decreases from 1 (inner) to 0 (outer)
        double t = (distance - innerEdge) / transitionWidth;
        double threshold = 1.0 - (t * t); // quadratic falloff for smoother transition
        double hash = coordHash(blockX, blockZ);
        return hash < threshold;
    }

    /**
     * Simple 2D value noise with smooth interpolation (2 octaves).
     * Returns a value between -1 and 1.
     * The scale adapts to zone radius so larger zones have larger noise features.
     */
    private static double sampleNoise(int x, int z, double radius) {
        // Noise feature size scales with zone radius
        double scale = Math.max(24.0, radius * 0.25);
        double nx = x / scale;
        double nz = z / scale;

        // Grid cell coordinates
        int ix = (int) Math.floor(nx);
        int iz = (int) Math.floor(nz);
        double fx = nx - ix;
        double fz = nz - iz;

        // Smoothstep interpolation
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);

        // Value noise at 4 corners
        double v00 = hashToDouble(ix, iz);
        double v10 = hashToDouble(ix + 1, iz);
        double v01 = hashToDouble(ix, iz + 1);
        double v11 = hashToDouble(ix + 1, iz + 1);

        // Bilinear interpolation
        double v0 = v00 + (v10 - v00) * fx;
        double v1 = v01 + (v11 - v01) * fx;
        double value = v0 + (v1 - v0) * fz; // 0..1

        // Second octave at half scale for more detail
        double scale2 = scale * 0.5;
        double nx2 = x / scale2;
        double nz2 = z / scale2;
        int ix2 = (int) Math.floor(nx2);
        int iz2 = (int) Math.floor(nz2);
        double fx2 = nx2 - ix2;
        double fz2 = nz2 - iz2;
        fx2 = fx2 * fx2 * (3 - 2 * fx2);
        fz2 = fz2 * fz2 * (3 - 2 * fz2);
        double w00 = hashToDouble(ix2 + 137, iz2 + 259);
        double w10 = hashToDouble(ix2 + 138, iz2 + 259);
        double w01 = hashToDouble(ix2 + 137, iz2 + 260);
        double w11 = hashToDouble(ix2 + 138, iz2 + 260);
        double w0 = w00 + (w10 - w00) * fx2;
        double w1 = w01 + (w11 - w01) * fx2;
        double value2 = w0 + (w1 - w0) * fz2;

        // Combine: first octave dominant, second adds detail
        double combined = value * 0.7 + value2 * 0.3;

        // Map from 0..1 to -1..1
        return combined * 2.0 - 1.0;
    }

    /**
     * Hash a 2D integer coordinate to a double in [0, 1).
     */
    private static double hashToDouble(int x, int z) {
        long h = x * 3129871L ^ (long) z * 116129781L;
        h = h * h * 42317861L + h * 11L;
        return ((h >> 16) & 0xFFFFL) / 65536.0;
    }

    /**
     * Hash a block coordinate to a double in [0, 1) for transition probability.
     * Uses different constants to avoid correlation with the noise function.
     */
    private static double coordHash(int x, int z) {
        long h = x * 6364136223846793005L ^ (long) z * 1442695040888963407L;
        h = h * h * 6364136223846793005L + h * 1442695040888963407L;
        return ((h >> 16) & 0xFFFFL) / 65536.0;
    }

    @Override
    public String toString() {
        return "ForcedBiomeZone{" + description + " at (" + centerX + ", " + centerZ + "), size=" + size + "}";
    }
}
