package net.denlille.bounded_worlds.biome;

import net.minecraft.world.level.biome.Climate;

/**
 * The climate values (quantized, vanilla scale) a forced zone morphs the
 * sampled climate toward, so vanilla itself selects the target biome inside
 * the zone and legitimate intermediate biomes in the halo around it.
 *
 * Depth is deliberately absent: it depends on Y and must always come from the
 * real sample (caves below a forced zone stay caves).
 *
 * Immutable — safe to read from worldgen threads.
 */
public record ZoneClimateTarget(
        long temperature,
        long humidity,
        long continentalness,
        long erosion,
        long weirdness
) {
    /**
     * Builds a target from the midpoint of each parameter range, which lies
     * strictly inside the biome's box and therefore selects it at distance 0.
     */
    public static ZoneClimateTarget fromParameterPoint(Climate.ParameterPoint point) {
        return new ZoneClimateTarget(
                midpoint(point.temperature()),
                midpoint(point.humidity()),
                midpoint(point.continentalness()),
                midpoint(point.erosion()),
                midpoint(point.weirdness())
        );
    }

    private static long midpoint(Climate.Parameter parameter) {
        return (parameter.min() + parameter.max()) / 2;
    }
}
