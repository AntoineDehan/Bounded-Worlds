package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Represents a forced biome zone with organic circular shape.
 * Immutable — safe to read from worldgen threads.
 */
public final class ForcedBiomeZone {

    /**
     * How the terrain inside the zone is reshaped when it doesn't match the
     * biome (a land biome placed over ocean, or an ocean biome over land).
     * NONE = the terrain already fits, leave it alone.
     */
    public enum TerrainShaping {
        NONE(0),
        // Raise the floor to just above sea level — existing hills are kept
        RAISE_ISLAND(67),
        // Carve down to an ocean floor — existing deeper spots are kept
        CARVE_BASIN(42);

        private final int targetSurfaceY;

        TerrainShaping(int targetSurfaceY) {
            this.targetSurfaceY = targetSurfaceY;
        }

        public int targetSurfaceY() {
            return targetSurfaceY;
        }
    }

    // Noise distorts the circular boundary by ±20% of the radius
    private static final double NOISE_AMPLITUDE = 0.2;
    // Climate morphing halo around the zone: bounds for the fade-out band width
    private static final double MIN_HALO_WIDTH = 24.0;
    private static final double MAX_HALO_WIDTH = 64.0;
    private static final double HALO_FRACTION = 0.4;

    // Core zone data
    private final int centerX;
    private final int centerZ;
    private final int size;
    private final Holder<Biome> biome;
    private final String description;
    // Climate values the halo morphs toward; null = hard-override-only zone
    @javax.annotation.Nullable
    private final ZoneClimateTarget climateTarget;
    // How the terrain is reshaped when it doesn't fit the biome
    private final TerrainShaping terrainShaping;

    // Pre-computed derived values for hot-path performance
    private final double radius;
    private final double haloWidth;
    private final double maxPossibleRadiusSq;
    private final double morphMaxRadiusSq;
    private final double minPossibleRadiusSq;
    // Zone-specific noise offsets for visual variety between zones
    private final int noiseOffsetX;
    private final int noiseOffsetZ;

    public ForcedBiomeZone(int centerX, int centerZ, int size, Holder<Biome> biome, String description) {
        this(centerX, centerZ, size, biome, description, null, TerrainShaping.NONE);
    }

    public ForcedBiomeZone(int centerX, int centerZ, int size, Holder<Biome> biome, String description,
                           @javax.annotation.Nullable ZoneClimateTarget climateTarget) {
        this(centerX, centerZ, size, biome, description, climateTarget, TerrainShaping.NONE);
    }

    public ForcedBiomeZone(int centerX, int centerZ, int size, Holder<Biome> biome, String description,
                           @javax.annotation.Nullable ZoneClimateTarget climateTarget, TerrainShaping terrainShaping) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = size;
        this.biome = biome;
        this.description = description;
        this.climateTarget = climateTarget;
        this.terrainShaping = terrainShaping;

        this.radius = size / 2.0;
        this.haloWidth = Math.min(MAX_HALO_WIDTH, Math.max(MIN_HALO_WIDTH, radius * HALO_FRACTION));

        // Pre-compute squared radii for fast reject/accept without sqrt
        double maxR = radius * (1.0 + NOISE_AMPLITUDE);
        this.maxPossibleRadiusSq = maxR * maxR;
        double morphMaxR = maxR + haloWidth;
        this.morphMaxRadiusSq = morphMaxR * morphMaxR;

        double minR = radius * (1.0 - NOISE_AMPLITUDE);
        this.minPossibleRadiusSq = minR * minR;

        // Derive noise offsets from zone center for unique shapes per zone
        this.noiseOffsetX = centerX * 7 + centerZ * 13;
        this.noiseOffsetZ = centerZ * 7 + centerX * 17;
    }

    // Accessors
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int size() { return size; }
    public Holder<Biome> biome() { return biome; }
    public String description() { return description; }
    @javax.annotation.Nullable
    public ZoneClimateTarget climateTarget() { return climateTarget; }
    public TerrainShaping terrainShaping() { return terrainShaping; }

    /**
     * Largest possible extent of the zone including noise distortion and the
     * climate morphing halo — used by planners for spacing constraints.
     */
    public double maxFootprint() {
        return radius * (1.0 + NOISE_AMPLITUDE) + haloWidth;
    }

    /** Same as {@link #maxFootprint()} for a zone that does not exist yet. */
    public static double maxFootprint(int size) {
        double r = size / 2.0;
        double halo = Math.min(MAX_HALO_WIDTH, Math.max(MIN_HALO_WIDTH, r * HALO_FRACTION));
        return r * (1.0 + NOISE_AMPLITUDE) + halo;
    }

    /**
     * Checks if a block position falls within this zone.
     * Deterministic noised circular boundary — the visual blending at edges is
     * handled by the climate morphing halo (see morphFactor), not by dithering.
     */
    public boolean contains(int blockX, int blockZ) {
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;

        // Compare squared distances first — avoids sqrt in ~95% of calls
        double distSq = dx * dx + dz * dz;

        // Quick reject
        if (distSq > maxPossibleRadiusSq) return false;

        // Quick accept
        if (distSq < minPossibleRadiusSq) return true;

        // Now compute sqrt only for the borderline cases
        double distance = Math.sqrt(distSq);

        // Noise uses zone-specific offsets for unique shapes
        double noiseValue = sampleNoise(blockX + noiseOffsetX, blockZ + noiseOffsetZ, radius);
        double effectiveRadius = radius + noiseValue * radius * NOISE_AMPLITUDE;

        return distance <= effectiveRadius;
    }

    /**
     * Strength of the climate morphing at a block position: 1 inside the zone,
     * fading smoothly to 0 across the halo band outside the noised boundary.
     * Always 0 when the zone has no climate target.
     */
    public double morphFactor(int blockX, int blockZ) {
        if (climateTarget == null) return 0;
        return edgeFactor(blockX, blockZ);
    }

    /**
     * Pure edge geometry: 1 inside the zone, smoothstep fade to 0 across the
     * halo. Shared by climate morphing and terrain shaping.
     */
    public double edgeFactor(int blockX, int blockZ) {
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;
        double distSq = dx * dx + dz * dz;

        if (distSq > morphMaxRadiusSq) return 0;
        if (distSq < minPossibleRadiusSq) return 1;

        double distance = Math.sqrt(distSq);
        double noiseValue = sampleNoise(blockX + noiseOffsetX, blockZ + noiseOffsetZ, radius);
        double effectiveRadius = radius + noiseValue * radius * NOISE_AMPLITUDE;

        if (distance <= effectiveRadius) return 1;
        if (distance >= effectiveRadius + haloWidth) return 0;

        double t = 1.0 - (distance - effectiveRadius) / haloWidth;
        return t * t * (3 - 2 * t); // smoothstep for a gradual climate blend
    }

    /**
     * Simple 2D value noise with smooth interpolation (2 octaves).
     * Returns a value between -1 and 1.
     * The scale adapts to zone radius so larger zones have larger noise features.
     * Also used by ZoneTerrainShaper to give reshaped terrain gentle hills.
     */
    public static double sampleNoise(int x, int z, double radius) {
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

    @Override
    public String toString() {
        return "ForcedBiomeZone{" + description + " at (" + centerX + ", " + centerZ + "), size=" + size + "}";
    }
}
