package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BiomeZonePlanner {

    private static final TagKey<Biome> IS_HOT = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_hot/overworld"));
    private static final TagKey<Biome> IS_COLD = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_cold/overworld"));

    public static List<ForcedBiomeZone> planZones(
            ServerLevel level,
            List<BiomeRequirement> missing,
            BiomeScanner.ScanResult scanResult,
            int worldRadius,
            BiomeZoneSize sizeCategory) {

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        long worldSeed = level.getSeed();
        Random random = new Random(worldSeed);

        BlockPos spawnPos = level.getSharedSpawnPos();
        int centerX = spawnPos.getX();
        int centerZ = spawnPos.getZ();

        List<ForcedBiomeZone> plannedZones = new ArrayList<>();

        for (BiomeRequirement req : missing) {
            // 1. Pick a concrete biome for this requirement
            Holder<Biome> chosenBiome = pickBiome(req, biomeRegistry, random);
            if (chosenBiome == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not find any biome for requirement: {}", req.description());
                continue;
            }

            String biomeName = chosenBiome.unwrapKey()
                    .map(ResourceKey::location)
                    .map(ResourceLocation::toString)
                    .orElse("unknown");

            // 2. Generate a random size for this zone
            int zoneSize = sizeCategory.randomSize(random);

            // 3. Determine temperature category of the chosen biome
            TemperatureCategory tempCategory = getTemperatureCategory(chosenBiome);

            // 4. Find a compatible location
            BlockPos placement = findCompatibleLocation(
                    tempCategory, scanResult.biomeLocations(),
                    centerX, centerZ, worldRadius, zoneSize, plannedZones, random);

            if (placement == null) {
                // Fallback: place at a random position within the radius
                placement = findFallbackLocation(centerX, centerZ, worldRadius, zoneSize, plannedZones, random);
            }

            if (placement == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not find placement for: {} ({})", req.description(), biomeName);
                continue;
            }

            String desc = biomeName + " for " + req.description();
            ForcedBiomeZone zone = new ForcedBiomeZone(placement.getX(), placement.getZ(), zoneSize, chosenBiome, desc);
            plannedZones.add(zone);

            BoundedWorlds.LOGGER.info("[Bounded Worlds] Planned forced zone: {} at ({}, {}), size {} ({})",
                    desc, placement.getX(), placement.getZ(), zoneSize, sizeCategory.name());
        }

        return plannedZones;
    }

    @Nullable
    private static Holder<Biome> pickBiome(BiomeRequirement req, Registry<Biome> registry, Random random) {
        if (!req.isTag()) {
            // Direct biome ID — look it up
            ResourceLocation biomeId = req.biomeId();
            if (biomeId == null) return null;
            return registry.getHolder(ResourceKey.create(Registries.BIOME, biomeId)).orElse(null);
        }

        // Tag — collect all biomes with this tag and pick one randomly
        TagKey<Biome> tagKey = req.tagKey();
        if (tagKey == null) return null;

        List<Holder<Biome>> tagged = new ArrayList<>();
        registry.getTagOrEmpty(tagKey).forEach(tagged::add);

        if (tagged.isEmpty()) return null;
        return tagged.get(random.nextInt(tagged.size()));
    }

    private static TemperatureCategory getTemperatureCategory(Holder<Biome> biome) {
        if (biome.is(IS_HOT)) return TemperatureCategory.HOT;
        if (biome.is(IS_COLD)) return TemperatureCategory.COLD;
        return TemperatureCategory.TEMPERATE;
    }

    @Nullable
    private static BlockPos findCompatibleLocation(
            TemperatureCategory targetTemp,
            Map<Holder<Biome>, BlockPos> biomeLocations,
            int centerX, int centerZ, int worldRadius, int zoneSize,
            List<ForcedBiomeZone> existingZones, Random random) {

        // Find all existing biomes of the same temperature category
        List<BlockPos> compatiblePositions = new ArrayList<>();
        for (Map.Entry<Holder<Biome>, BlockPos> entry : biomeLocations.entrySet()) {
            if (getTemperatureCategory(entry.getKey()) == targetTemp) {
                compatiblePositions.add(entry.getValue());
            }
        }

        if (compatiblePositions.isEmpty()) return null;

        // I4 fix: Sort deterministically before shuffling (HashMap iteration order is random)
        compatiblePositions.sort((a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX()) : Integer.compare(a.getZ(), b.getZ()));

        // Shuffle for variety (seed-dependent, but from a stable starting order)
        List<BlockPos> shuffled = new ArrayList<>(compatiblePositions);
        java.util.Collections.shuffle(shuffled, random);

        int[][] offsets = {{zoneSize, 0}, {-zoneSize, 0}, {0, zoneSize}, {0, -zoneSize}};

        for (BlockPos base : shuffled) {
            for (int[] offset : offsets) {
                int px = base.getX() + offset[0];
                int pz = base.getZ() + offset[1];

                if (isValidPlacement(px, pz, centerX, centerZ, worldRadius, zoneSize, existingZones)) {
                    return new BlockPos(px, 0, pz);
                }
            }
        }

        return null;
    }

    @Nullable
    private static BlockPos findFallbackLocation(
            int centerX, int centerZ, int worldRadius, int zoneSize,
            List<ForcedBiomeZone> existingZones, Random random) {

        // Try random positions within the radius
        for (int attempt = 0; attempt < 50; attempt++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist = (worldRadius * 0.5) + (random.nextDouble() * worldRadius * 0.4);
            int px = centerX + (int) (Math.cos(angle) * dist);
            int pz = centerZ + (int) (Math.sin(angle) * dist);

            if (isValidPlacement(px, pz, centerX, centerZ, worldRadius, zoneSize, existingZones)) {
                return new BlockPos(px, 0, pz);
            }
        }

        return null;
    }

    private static boolean isValidPlacement(int px, int pz, int centerX, int centerZ,
                                             int worldRadius, int zoneSize,
                                             List<ForcedBiomeZone> existingZones) {
        // Max extent of the circular zone with noise distortion (20% amplitude)
        double maxRadius = (zoneSize / 2.0) * 1.2 + 16; // +16 for transition zone

        // Check that the zone fits within the world radius
        long dx = (long)(px - centerX);
        long dz = (long)(pz - centerZ);
        double distFromCenter = Math.sqrt(dx * dx + dz * dz);
        if (distFromCenter + maxRadius > worldRadius) {
            return false;
        }

        // Check no overlap with existing forced zones (circular distance check)
        for (ForcedBiomeZone existing : existingZones) {
            double existingMaxRadius = (existing.size() / 2.0) * 1.2 + 16;
            double minDist = existingMaxRadius + maxRadius + 32; // 32 blocks padding between zones
            double edx = px - existing.centerX();
            double edz = pz - existing.centerZ();
            if (Math.sqrt(edx * edx + edz * edz) < minDist) {
                return false;
            }
        }

        return true;
    }

    private enum TemperatureCategory {
        HOT, COLD, TEMPERATE
    }
}
