package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ForcedBiomeZoneManager {

    // CopyOnWriteArrayList for thread-safety (server thread writes, worldgen threads read)
    private static final List<ForcedBiomeZone> zones = new CopyOnWriteArrayList<>();

    // Overworld biome source / climate sampler instances, captured at server start.
    // The mixins compare against these so forced zones, directional bias and
    // climate morphing never leak into the Nether or End (both also use
    // MultiNoiseBiomeSource / Climate.Sampler at the same block coordinates).
    private static volatile BiomeSource overworldBiomeSource;
    private static volatile Climate.Sampler overworldSampler;
    private static volatile Object overworldRandomState;
    // Config kill-switch for terrain shaping, captured at server start
    private static volatile boolean terrainShapingEnabled;
    // Cached so the density-function hot path can bail with one volatile read
    private static volatile boolean hasShapingZones;

    // Below this block Y, zones with a climate target stop hard-overriding:
    // the morphed climate still selects the target biome near the surface while
    // letting vanilla cave biomes (deep dark, lush caves...) exist underneath.
    private static final int HARD_OVERRIDE_MIN_Y = 60;

    public static void clear() {
        zones.clear();
        overworldBiomeSource = null;
        overworldSampler = null;
        overworldRandomState = null;
        hasShapingZones = false;
    }

    public static void setOverworldContext(BiomeSource biomeSource, @Nullable Climate.Sampler sampler,
                                           @Nullable Object randomState) {
        overworldBiomeSource = biomeSource;
        overworldSampler = sampler;
        overworldRandomState = randomState;
    }

    public static void setTerrainShapingEnabled(boolean enabled) {
        terrainShapingEnabled = enabled;
    }

    public static boolean isOverworldBiomeSource(Object source) {
        BiomeSource captured = overworldBiomeSource;
        return captured != null && captured == source;
    }

    public static boolean isOverworldSampler(Object sampler) {
        Climate.Sampler captured = overworldSampler;
        return captured != null && captured == sampler;
    }

    /**
     * Fast gate for the terrain-shaping density function: true only when the
     * feature is enabled, shaping zones exist, and the calling RandomState is
     * the overworld's (terrain shaping must never leak into other dimensions).
     */
    public static boolean isTerrainShapingActive(Object randomState) {
        if (!terrainShapingEnabled || !hasShapingZones) return false;
        Object captured = overworldRandomState;
        return captured != null && captured == randomState;
    }

    /** A terrain reshaping to apply at a position: blend strength + mode. */
    public record TerrainShape(double factor, ForcedBiomeZone.TerrainShaping mode) {}

    /**
     * The terrain shaping affecting a block position, or null if none.
     * Zones are spaced apart by the planners, so at most one zone applies.
     */
    @Nullable
    public static TerrainShape getTerrainShapeAt(int blockX, int blockZ) {
        for (ForcedBiomeZone zone : zones) {
            if (zone.terrainShaping() == ForcedBiomeZone.TerrainShaping.NONE) continue;
            double factor = zone.edgeFactor(blockX, blockZ);
            if (factor > 0) {
                return new TerrainShape(factor, zone.terrainShaping());
            }
        }
        return null;
    }

    public static void addZone(ForcedBiomeZone zone) {
        zones.add(zone);
        if (zone.terrainShaping() != ForcedBiomeZone.TerrainShaping.NONE) {
            hasShapingZones = true;
        }
    }

    public static List<ForcedBiomeZone> getZones() {
        return Collections.unmodifiableList(zones);
    }

    @Nullable
    public static Holder<Biome> getBiomeAt(int blockX, int blockY, int blockZ) {
        for (ForcedBiomeZone zone : zones) {
            // Morph-target zones only need the hard override near the surface —
            // below, the morphed climate keeps the guarantee (depth is untouched)
            // and cave biomes survive. Zones without a target have no morphing,
            // so they keep the full-column override.
            if (zone.climateTarget() != null && blockY < HARD_OVERRIDE_MIN_Y) {
                continue;
            }
            if (zone.contains(blockX, blockZ)) {
                return zone.biome();
            }
        }
        return null;
    }

    /** A climate morphing to apply at a position: blend strength + target values. */
    public record ClimateMorph(double factor, ZoneClimateTarget target) {}

    /**
     * The climate morphing affecting a block position, or null if none.
     * Zones are spaced apart by the planners, so at most one zone applies.
     */
    @Nullable
    public static ClimateMorph getMorphAt(int blockX, int blockZ) {
        for (ForcedBiomeZone zone : zones) {
            ZoneClimateTarget target = zone.climateTarget();
            if (target == null) continue;
            double factor = zone.morphFactor(blockX, blockZ);
            if (factor > 0) {
                return new ClimateMorph(factor, target);
            }
        }
        return null;
    }
}
