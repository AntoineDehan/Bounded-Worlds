package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-dimension registry of forced biome zones. Each supported dimension gets
 * an entry created at server start, holding its zones plus the identity of its
 * worldgen instances (biome source, climate sampler, random state) — the
 * mixins resolve their dimension by identity lookup, so effects never leak
 * into dimensions that have no entry (e.g. the End, modded dimensions).
 */
public class ForcedBiomeZoneManager {

    /** Zones and captured worldgen instances for one dimension. */
    public static final class DimensionEntry {
        private final ResourceLocation dimensionId;
        private final boolean overworld;
        private final BiomeSource biomeSource;
        @Nullable private final Climate.Sampler sampler;
        @Nullable private final Object randomState;
        // Below this block Y, zones with a climate target stop hard-overriding
        // (the morphed climate keeps the guarantee while cave biomes survive).
        // Derived from the dimension's sea level so low-surface custom
        // dimensions are covered too.
        private final int hardOverrideMinY;
        // CopyOnWriteArrayList for thread-safety (server thread writes, worldgen threads read)
        private final List<ForcedBiomeZone> zones = new CopyOnWriteArrayList<>();
        private volatile boolean hasShapingZones;

        private DimensionEntry(ResourceLocation dimensionId, boolean overworld, BiomeSource biomeSource,
                               @Nullable Climate.Sampler sampler, @Nullable Object randomState,
                               int hardOverrideMinY) {
            this.dimensionId = dimensionId;
            this.overworld = overworld;
            this.biomeSource = biomeSource;
            this.sampler = sampler;
            this.randomState = randomState;
            this.hardOverrideMinY = hardOverrideMinY;
        }

        public ResourceLocation dimensionId() { return dimensionId; }
        public boolean isOverworld() { return overworld; }
        public List<ForcedBiomeZone> zones() { return Collections.unmodifiableList(zones); }

        public void addZone(ForcedBiomeZone zone) {
            zones.add(zone);
            if (zone.terrainShaping() != ForcedBiomeZone.TerrainShaping.NONE) {
                hasShapingZones = true;
            }
        }
    }

    private static final List<DimensionEntry> entries = new CopyOnWriteArrayList<>();
    // Config kill-switch for terrain shaping, captured at server start
    private static volatile boolean terrainShapingEnabled;

    public static void clear() {
        entries.clear();
    }

    public static void setTerrainShapingEnabled(boolean enabled) {
        terrainShapingEnabled = enabled;
    }

    /** Creates and registers the entry for a dimension, capturing its worldgen instances. */
    public static DimensionEntry register(ServerLevel level) {
        Climate.Sampler sampler = null;
        Object randomState = null;
        try {
            randomState = level.getChunkSource().randomState();
            sampler = level.getChunkSource().randomState().sampler();
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not obtain Climate.Sampler for {}: {}",
                    level.dimension().location(), e.getMessage());
        }

        DimensionEntry entry = new DimensionEntry(
                level.dimension().location(),
                level.dimension() == Level.OVERWORLD,
                level.getChunkSource().getGenerator().getBiomeSource(),
                sampler, randomState,
                level.getChunkSource().getGenerator().getSeaLevel() - 3);
        entries.add(entry);
        return entry;
    }

    public static List<DimensionEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** The entry registered for this level's dimension, or null. */
    @Nullable
    public static DimensionEntry entryFor(ServerLevel level) {
        ResourceLocation id = level.dimension().location();
        for (DimensionEntry entry : entries) {
            if (entry.dimensionId.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** Resolves the dimension entry owning this Climate.Sampler instance, or null. */
    @Nullable
    public static DimensionEntry entryForSampler(Object sampler) {
        for (DimensionEntry entry : entries) {
            if (entry.sampler == sampler) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private static DimensionEntry entryForBiomeSource(Object biomeSource) {
        for (DimensionEntry entry : entries) {
            if (entry.biomeSource == biomeSource) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private static DimensionEntry entryForRandomState(Object randomState) {
        for (DimensionEntry entry : entries) {
            if (entry.randomState != null && entry.randomState == randomState) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The forced biome at a position for the given biome source instance, or
     * null. Returns null for biome sources of unregistered dimensions.
     */
    @Nullable
    public static Holder<Biome> getBiomeAt(Object biomeSource, int blockX, int blockY, int blockZ) {
        DimensionEntry entry = entryForBiomeSource(biomeSource);
        if (entry == null) return null;

        for (ForcedBiomeZone zone : entry.zones) {
            // Morph-target zones only need the hard override near the surface —
            // below, the morphed climate keeps the guarantee (depth is untouched)
            // and cave biomes survive. Zones without a target have no morphing,
            // so they keep the full-column override.
            if (zone.climateTarget() != null && blockY < entry.hardOverrideMinY) {
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
     * The climate morphing affecting a block position in this dimension, or null.
     * Zones are spaced apart by the planners, so at most one zone applies.
     */
    @Nullable
    public static ClimateMorph getMorphAt(DimensionEntry entry, int blockX, int blockZ) {
        for (ForcedBiomeZone zone : entry.zones) {
            ZoneClimateTarget target = zone.climateTarget();
            if (target == null) continue;
            double factor = zone.morphFactor(blockX, blockZ);
            if (factor > 0) {
                return new ClimateMorph(factor, target);
            }
        }
        return null;
    }

    /** A terrain reshaping to apply at a position: blend strength + mode. */
    public record TerrainShape(double factor, ForcedBiomeZone.TerrainShaping mode) {}

    /**
     * Fast gate for the terrain-shaping density function: true only when the
     * feature is enabled and the calling RandomState belongs to a registered
     * dimension that actually has shaping zones.
     */
    public static boolean isTerrainShapingActive(Object randomState) {
        if (!terrainShapingEnabled) return false;
        DimensionEntry entry = entryForRandomState(randomState);
        return entry != null && entry.hasShapingZones;
    }

    /**
     * The terrain shaping affecting a block position for the given RandomState
     * instance, or null if none.
     */
    @Nullable
    public static TerrainShape getTerrainShapeAt(Object randomState, int blockX, int blockZ) {
        DimensionEntry entry = entryForRandomState(randomState);
        if (entry == null) return null;

        for (ForcedBiomeZone zone : entry.zones) {
            if (zone.terrainShaping() == ForcedBiomeZone.TerrainShaping.NONE) continue;
            double factor = zone.edgeFactor(blockX, blockZ);
            if (factor > 0) {
                return new TerrainShape(factor, zone.terrainShaping());
            }
        }
        return null;
    }
}
