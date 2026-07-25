package net.denlille.bounded_worlds.biome;

import com.mojang.datafixers.util.Pair;
import net.denlille.bounded_worlds.mixin.MultiNoiseBiomeSourceAccessor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Matches sampled climate values against a biome's parameter points, so forced
 * zones can be placed where the existing terrain already fits the target biome
 * (a forced ocean lands on an existing water basin, forced peaks on mountains).
 */
public final class ClimateMatcher {

    private ClimateMatcher() {}

    // Vanilla boundary between ocean and coast continentalness (OverworldBiomeBuilder)
    private static final long COAST_THRESHOLD = Climate.quantizeCoord(-0.19f);

    /** What kind of terrain a biome needs so it doesn't end up invisible. */
    public enum TerrainKind { OCEANIC, LAND, EITHER }

    /**
     * Classifies a biome's terrain needs from its continentalness ranges:
     * fully below the coast threshold → OCEANIC, fully above → LAND,
     * points on both sides → EITHER.
     */
    public static TerrainKind targetTerrainKind(List<Climate.ParameterPoint> points) {
        boolean anyOcean = false;
        boolean anyLand = false;
        for (Climate.ParameterPoint point : points) {
            if (point.continentalness().max() <= COAST_THRESHOLD) {
                anyOcean = true;
            } else {
                anyLand = true;
            }
        }
        if (anyOcean && anyLand) return TerrainKind.EITHER;
        return anyOcean ? TerrainKind.OCEANIC : TerrainKind.LAND;
    }

    /** Whether the sampled position sits on oceanic terrain (below coast continentalness). */
    public static boolean isOceanicSample(Climate.TargetPoint sample) {
        return sample.continentalness() < COAST_THRESHOLD;
    }

    /** Whether a sampled position's terrain is compatible with the given kind. */
    public static boolean matchesTerrainKind(Climate.TargetPoint sample, TerrainKind kind) {
        return switch (kind) {
            case EITHER -> true;
            case OCEANIC -> sample.continentalness() < COAST_THRESHOLD;
            case LAND -> sample.continentalness() >= COAST_THRESHOLD;
        };
    }

    /**
     * Returns the climate parameter points that select the given biome in the
     * vanilla multi-noise parameter list. Empty if the biome source is not
     * multi-noise or the biome has no entry (e.g. TerraBlender-only biomes) —
     * callers must fall back to category-based placement in that case.
     */
    public static List<Climate.ParameterPoint> getParameterPoints(Holder<Biome> biome, BiomeSource biomeSource) {
        if (!(biomeSource instanceof MultiNoiseBiomeSource multiNoise)) {
            return List.of();
        }

        Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        if (key.isEmpty()) {
            return List.of();
        }

        try {
            Climate.ParameterList<Holder<Biome>> parameters =
                    ((MultiNoiseBiomeSourceAccessor) multiNoise).boundedWorlds$parameters();
            List<Climate.ParameterPoint> points = new ArrayList<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : parameters.values()) {
                if (pair.getSecond().is(key.get())) {
                    points.add(pair.getFirst());
                }
            }
            return points;
        } catch (Exception e) {
            // Custom biome sources may throw when resolving parameters
            return List.of();
        }
    }

    /**
     * Squared climate distance between a sampled point and a target parameter
     * point, using the same per-dimension metric as vanilla's RTree lookup.
     * The depth parameter is ignored: it depends on the sampling Y and is not
     * meaningful for choosing a horizontal placement.
     */
    public static long distanceSq(Climate.TargetPoint sampled, Climate.ParameterPoint target) {
        long d = 0;
        d += square(distanceTo(target.temperature(), sampled.temperature()));
        d += square(distanceTo(target.humidity(), sampled.humidity()));
        d += square(distanceTo(target.continentalness(), sampled.continentalness()));
        d += square(distanceTo(target.erosion(), sampled.erosion()));
        d += square(distanceTo(target.weirdness(), sampled.weirdness()));
        d += square(target.offset());
        return d;
    }

    /**
     * Smallest squared distance from the sampled point to any of the target points.
     */
    public static long bestDistanceSq(Climate.TargetPoint sampled, List<Climate.ParameterPoint> targets) {
        long best = Long.MAX_VALUE;
        for (Climate.ParameterPoint target : targets) {
            best = Math.min(best, distanceSq(sampled, target));
        }
        return best;
    }

    /**
     * The parameter point closest to the sampled climate (first wins ties —
     * deterministic as long as the input list order is stable).
     */
    @Nullable
    public static Climate.ParameterPoint nearestParameterPoint(Climate.TargetPoint sampled,
                                                               List<Climate.ParameterPoint> points) {
        Climate.ParameterPoint best = null;
        long bestDist = Long.MAX_VALUE;
        for (Climate.ParameterPoint point : points) {
            long dist = distanceSq(sampled, point);
            if (dist < bestDist) {
                bestDist = dist;
                best = point;
            }
        }
        return best;
    }

    /**
     * Builds the climate target a forced zone should morph toward: the biome's
     * parameter point closest to the natural climate at the zone position
     * (minimal disturbance), reduced to its midpoint values. Null when the
     * biome has no parameter points — the zone then stays a hard override.
     */
    @Nullable
    public static ZoneClimateTarget computeMorphTarget(Holder<Biome> biome, BiomeSource biomeSource,
                                                       @Nullable Climate.Sampler sampler,
                                                       int blockX, int blockZ) {
        List<Climate.ParameterPoint> points = getParameterPoints(biome, biomeSource);
        if (points.isEmpty()) {
            return null;
        }
        Climate.ParameterPoint chosen;
        if (sampler != null) {
            Climate.TargetPoint sampled = sampler.sample(blockX >> 2, 64 >> 2, blockZ >> 2);
            chosen = nearestParameterPoint(sampled, points);
        } else {
            chosen = points.get(0);
        }
        return chosen == null ? null : ZoneClimateTarget.fromParameterPoint(chosen);
    }

    private static long distanceTo(Climate.Parameter parameter, long value) {
        if (value < parameter.min()) return parameter.min() - value;
        if (value > parameter.max()) return value - parameter.max();
        return 0;
    }

    private static long square(long v) {
        return v * v;
    }
}
