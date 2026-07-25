package net.denlille.bounded_worlds.biome;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Wraps the overworld's terrain density functions (finalDensity and
 * initialDensityWithoutJaggedness) to reshape terrain inside forced zones
 * whose natural terrain doesn't fit the biome:
 *
 * - RAISE_ISLAND: one-sided max() against an upward ramp — the ocean floor
 *   rises to just above sea level (a forced mushroom island actually emerges)
 *   while existing terrain above the target is left untouched.
 * - CARVE_BASIN: one-sided min() — land is carved down to an ocean floor,
 *   existing deeper spots are kept.
 *
 * The effect blends over the zone's halo (same edge geometry as the climate
 * morphing) and fades out below Y~35 so deep caves stay vanilla. Outside
 * zones — or in any dimension other than the overworld — the wrapped function
 * is returned unchanged.
 */
public final class ZoneTerrainShaper implements DensityFunction {

    // Density ramp: strong enough to dominate vanilla density near the surface
    private static final double SLOPE_PER_BLOCK = 0.1;
    private static final double MAX_ABS_DENSITY = 2.0;
    // Shaping fades to zero between these Y levels so deep caves survive
    private static final double FADE_BOTTOM_Y = 20.0;
    private static final double FADE_TOP_Y = 35.0;

    private final DensityFunction wrapped;
    // Identity of the owning RandomState — gates shaping to the overworld
    private final Object owner;

    public ZoneTerrainShaper(DensityFunction wrapped, Object owner) {
        this.wrapped = wrapped;
        this.owner = owner;
    }

    @Override
    public double compute(FunctionContext context) {
        double value = wrapped.compute(context);
        if (!ForcedBiomeZoneManager.isTerrainShapingActive(owner)) {
            return value;
        }
        return shape(value, context.blockX(), context.blockY(), context.blockZ());
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        wrapped.fillArray(values, provider);
        if (!ForcedBiomeZoneManager.isTerrainShapingActive(owner)) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            FunctionContext context = provider.forIndex(i);
            values[i] = shape(values[i], context.blockX(), context.blockY(), context.blockZ());
        }
    }

    private static double shape(double value, int blockX, int blockY, int blockZ) {
        // Deep underground: leave vanilla caves alone
        if (blockY <= FADE_BOTTOM_Y) return value;

        ForcedBiomeZoneManager.TerrainShape shape = ForcedBiomeZoneManager.getTerrainShapeAt(blockX, blockZ);
        if (shape == null) return value;

        double depthFade = smoothstep((blockY - FADE_BOTTOM_Y) / (FADE_TOP_Y - FADE_BOTTOM_Y));

        double ramp = (shape.mode().targetSurfaceY() - blockY) * SLOPE_PER_BLOCK;
        ramp = Math.max(-MAX_ABS_DENSITY, Math.min(MAX_ABS_DENSITY, ramp));

        double shaped = shape.mode() == ForcedBiomeZone.TerrainShaping.RAISE_ISLAND
                ? Math.max(value, ramp)
                : Math.min(value, ramp);

        return value + shape.factor() * depthFade * (shaped - value);
    }

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3 - 2 * t);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Re-wrap so per-chunk wiring (NoiseChunk caches) applies to the inner
        // function while shaping stays on the outside
        return visitor.apply(new ZoneTerrainShaper(wrapped.mapAll(visitor), owner));
    }

    @Override
    public double minValue() {
        return Math.min(wrapped.minValue(), -MAX_ABS_DENSITY);
    }

    @Override
    public double maxValue() {
        return Math.max(wrapped.maxValue(), MAX_ABS_DENSITY);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // Never serialized — created at runtime, after deserialization
        return wrapped.codec();
    }
}
