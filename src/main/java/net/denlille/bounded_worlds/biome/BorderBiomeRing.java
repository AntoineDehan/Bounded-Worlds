package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * A ring of a fixed biome hugging the world border, Terraria-style: everything
 * beyond the inner radius (all the way to — and past — the world border) becomes
 * the configured biome. The inner coastline is distorted by the same value
 * noise as forced zones, the climate morphs across an inward halo so vanilla
 * generates legitimate transition biomes, and the terrain is reshaped one-sided
 * (a water biome carves a real sea, a land biome raises solid ground).
 *
 * Immutable — safe to read from worldgen threads.
 */
public final class BorderBiomeRing {

    // Climate morphing halo, extending inward from the noised inner edge
    private static final double HALO_WIDTH = 48.0;
    // Coastline distortion: ±32 blocks with ~100-block features
    private static final double NOISE_AMPLITUDE = 32.0;
    private static final double NOISE_SCALE_RADIUS = 400.0;

    private final int centerX;
    private final int centerZ;
    private final double innerRadius;
    private final Holder<Biome> biome;
    @Nullable
    private final ZoneClimateTarget climateTarget;
    private final ForcedBiomeZone.TerrainShaping terrainShaping;

    // Pre-computed squared bounds for fast reject/accept without sqrt
    private final double definitelyOutsideSq;
    private final double definitelyInsideSq;
    private final int noiseOffsetX;
    private final int noiseOffsetZ;

    public BorderBiomeRing(int centerX, int centerZ, double innerRadius, Holder<Biome> biome,
                           @Nullable ZoneClimateTarget climateTarget,
                           ForcedBiomeZone.TerrainShaping terrainShaping) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.innerRadius = innerRadius;
        this.biome = biome;
        this.climateTarget = climateTarget;
        this.terrainShaping = terrainShaping;

        double outside = innerRadius + NOISE_AMPLITUDE;
        this.definitelyOutsideSq = outside * outside;
        double inside = Math.max(0, innerRadius - NOISE_AMPLITUDE - HALO_WIDTH);
        this.definitelyInsideSq = inside * inside;

        this.noiseOffsetX = centerX * 11 + centerZ * 5;
        this.noiseOffsetZ = centerZ * 11 + centerX * 3;
    }

    /**
     * Shaping mode implied by the biome: one-sided ramps make this safe to
     * apply across mixed terrain — a water ring only carves land down (existing
     * oceans stay untouched), a land ring only fills water up.
     */
    public static ForcedBiomeZone.TerrainShaping shapingFor(Holder<Biome> biome) {
        boolean wantsWater = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
                || biome.is(BiomeTags.IS_RIVER);
        return wantsWater ? ForcedBiomeZone.TerrainShaping.CARVE_BASIN
                          : ForcedBiomeZone.TerrainShaping.RAISE_ISLAND;
    }

    public Holder<Biome> biome() { return biome; }
    @Nullable
    public ZoneClimateTarget climateTarget() { return climateTarget; }
    public ForcedBiomeZone.TerrainShaping terrainShaping() { return terrainShaping; }
    public double innerRadius() { return innerRadius; }

    /** Whether a block position lies in the ring (beyond the noised inner edge). */
    public boolean contains(int blockX, int blockZ) {
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;
        double distSq = dx * dx + dz * dz;

        if (distSq >= definitelyOutsideSq) return true;
        if (distSq <= definitelyInsideSq) return false;

        return Math.sqrt(distSq) >= effectiveInnerRadius(blockX, blockZ);
    }

    /**
     * Blend strength: 1 in the ring, fading smoothly to 0 across the halo
     * band inward of the noised coastline. Mirrors ForcedBiomeZone.edgeFactor.
     */
    public double edgeFactor(int blockX, int blockZ) {
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;
        double distSq = dx * dx + dz * dz;

        if (distSq >= definitelyOutsideSq) return 1;
        if (distSq <= definitelyInsideSq) return 0;

        double distance = Math.sqrt(distSq);
        double effectiveInner = effectiveInnerRadius(blockX, blockZ);

        if (distance >= effectiveInner) return 1;
        double t = 1.0 - (effectiveInner - distance) / HALO_WIDTH;
        if (t <= 0) return 0;
        return t * t * (3 - 2 * t); // smoothstep
    }

    private double effectiveInnerRadius(int blockX, int blockZ) {
        double noise = ForcedBiomeZone.sampleNoise(
                blockX + noiseOffsetX, blockZ + noiseOffsetZ, NOISE_SCALE_RADIUS);
        return innerRadius + noise * NOISE_AMPLITUDE;
    }

    @Override
    public String toString() {
        return "BorderBiomeRing{" + biome.unwrapKey().map(k -> k.location().toString()).orElse("?")
                + " beyond radius " + (int) innerRadius + ", terrain=" + terrainShaping + "}";
    }
}
