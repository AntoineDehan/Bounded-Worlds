package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ForcedBiomeZoneManager {

    // C1 fix: CopyOnWriteArrayList for thread-safety (server thread writes, worldgen threads read)
    private static final List<ForcedBiomeZone> zones = new CopyOnWriteArrayList<>();

    public static void clear() {
        zones.clear();
    }

    public static void addZone(ForcedBiomeZone zone) {
        zones.add(zone);
    }

    public static List<ForcedBiomeZone> getZones() {
        return Collections.unmodifiableList(zones);
    }

    @Nullable
    public static Holder<Biome> getBiomeAt(int blockX, int blockZ) {
        for (ForcedBiomeZone zone : zones) {
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
