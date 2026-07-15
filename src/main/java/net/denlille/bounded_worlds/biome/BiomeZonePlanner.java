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
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BiomeZonePlanner {

    public static List<ForcedBiomeZone> planZones(
            ServerLevel level,
            List<BiomeRequirement> missing,
            BiomeScanner.ScanResult scanResult,
            int worldRadius,
            BiomeZoneSize sizeCategory,
            @Nullable DirectionalPlacement directional) {

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
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

            // 3. Classify the biome (temperature + humidity)
            BiomeClimateClassifier.TemperatureCategory tempCategory = BiomeClimateClassifier.getTemperature(chosenBiome);
            BiomeClimateClassifier.HumidityCategory humidCategory = BiomeClimateClassifier.getHumidity(chosenBiome);

            // 4. Find a location — preferably where the existing climate already
            // fits the biome, so the terrain under the zone matches (no ocean
            // painted on hills, no peaks biome on flat ground).
            List<Climate.ParameterPoint> targetPoints = ClimateMatcher.getParameterPoints(chosenBiome, biomeSource);
            BlockPos placement = null;
            String placementMode = "climate-matched";

            if (!targetPoints.isEmpty() && !scanResult.climateSamples().isEmpty()) {
                placement = findClimateMatchedLocation(targetPoints, scanResult.climateSamples(),
                        centerX, centerZ, worldRadius, zoneSize, plannedZones,
                        directional, tempCategory, humidCategory);
            }

            if (placement == null) {
                placementMode = "category fallback";
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] No climate-compatible terrain found for {} — " +
                        "falling back to approximate placement, terrain may look artificial.", biomeName);
                placement = findCompatibleLocation(
                        tempCategory, humidCategory, scanResult.biomeLocations(),
                        centerX, centerZ, worldRadius, zoneSize, plannedZones, random, directional);
            }

            if (placement == null) {
                // Fallback: place at a random position in the correct region
                int maxAttempts = directional != null ? 100 : 50;
                placement = findFallbackLocation(centerX, centerZ, worldRadius, zoneSize,
                        plannedZones, random, directional, tempCategory, humidCategory, maxAttempts);
            }

            if (placement == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not find placement for: {} ({})", req.description(), biomeName);
                continue;
            }

            String dirInfo = directional != null ? " [" + tempCategory + "/" + humidCategory + "]" : "";
            String desc = biomeName + " for " + req.description();
            ForcedBiomeZone zone = new ForcedBiomeZone(placement.getX(), placement.getZ(), zoneSize, chosenBiome, desc);
            plannedZones.add(zone);

            BoundedWorlds.LOGGER.info("[Bounded Worlds] Planned forced zone: {} at ({}, {}), size {} ({}, {}){}",
                    desc, placement.getX(), placement.getZ(), zoneSize, sizeCategory.name(), placementMode, dirInfo);
        }

        return plannedZones;
    }

    @Nullable
    private static Holder<Biome> pickBiome(BiomeRequirement req, Registry<Biome> registry, Random random) {
        if (!req.isTag()) {
            ResourceLocation biomeId = req.biomeId();
            if (biomeId == null) return null;
            return registry.getHolder(ResourceKey.create(Registries.BIOME, biomeId)).orElse(null);
        }

        TagKey<Biome> tagKey = req.tagKey();
        if (tagKey == null) return null;

        List<Holder<Biome>> tagged = new ArrayList<>();
        registry.getTagOrEmpty(tagKey).forEach(tagged::add);

        if (tagged.isEmpty()) return null;
        return tagged.get(random.nextInt(tagged.size()));
    }

    /**
     * Picks the candidate position whose sampled climate is closest to any of the
     * biome's parameter points, among candidates satisfying the placement
     * constraints. Deterministic: candidates are iterated in scan order and only
     * a strictly better distance replaces the current best.
     */
    @Nullable
    private static BlockPos findClimateMatchedLocation(
            List<Climate.ParameterPoint> targetPoints,
            List<BiomeScanner.ClimateSample> candidates,
            int centerX, int centerZ, int worldRadius, int zoneSize,
            List<ForcedBiomeZone> existingZones,
            @Nullable DirectionalPlacement directional,
            BiomeClimateClassifier.TemperatureCategory targetTemp,
            BiomeClimateClassifier.HumidityCategory targetHumid) {

        long bestDist = Long.MAX_VALUE;
        BlockPos best = null;

        for (BiomeScanner.ClimateSample sample : candidates) {
            if (!isValidPlacement(sample.x(), sample.z(), centerX, centerZ, worldRadius, zoneSize, existingZones)) {
                continue;
            }
            if (directional != null && !directional.isInCorrectRegion(
                    sample.x(), sample.z(), centerX, centerZ, worldRadius, targetTemp, targetHumid)) {
                continue;
            }

            long dist = ClimateMatcher.bestDistanceSq(sample.climate(), targetPoints);
            if (dist < bestDist) {
                bestDist = dist;
                best = new BlockPos(sample.x(), 0, sample.z());
            }
        }

        if (best != null) {
            BoundedWorlds.LOGGER.debug("[Bounded Worlds] Best climate match at ({}, {}), distanceSq={}",
                    best.getX(), best.getZ(), bestDist);
        }
        return best;
    }

    @Nullable
    private static BlockPos findCompatibleLocation(
            BiomeClimateClassifier.TemperatureCategory targetTemp,
            BiomeClimateClassifier.HumidityCategory targetHumid,
            Map<Holder<Biome>, BlockPos> biomeLocations,
            int centerX, int centerZ, int worldRadius, int zoneSize,
            List<ForcedBiomeZone> existingZones, Random random,
            @Nullable DirectionalPlacement directional) {

        // Find all existing biomes of the same temperature category
        List<BlockPos> compatiblePositions = new ArrayList<>();
        for (Map.Entry<Holder<Biome>, BlockPos> entry : biomeLocations.entrySet()) {
            if (BiomeClimateClassifier.getTemperature(entry.getKey()) == targetTemp) {
                compatiblePositions.add(entry.getValue());
            }
        }

        if (compatiblePositions.isEmpty()) return null;

        // Sort deterministically before shuffling
        compatiblePositions.sort((a, b) -> a.getX() != b.getX()
                ? Integer.compare(a.getX(), b.getX())
                : Integer.compare(a.getZ(), b.getZ()));

        List<BlockPos> shuffled = new ArrayList<>(compatiblePositions);
        java.util.Collections.shuffle(shuffled, random);

        int[][] offsets = {{zoneSize, 0}, {-zoneSize, 0}, {0, zoneSize}, {0, -zoneSize}};

        for (BlockPos base : shuffled) {
            for (int[] offset : offsets) {
                int px = base.getX() + offset[0];
                int pz = base.getZ() + offset[1];

                if (!isValidPlacement(px, pz, centerX, centerZ, worldRadius, zoneSize, existingZones)) {
                    continue;
                }

                // Directional check: must be in the correct region
                if (directional != null && !directional.isInCorrectRegion(
                        px, pz, centerX, centerZ, worldRadius, targetTemp, targetHumid)) {
                    continue;
                }

                return new BlockPos(px, 0, pz);
            }
        }

        return null;
    }

    @Nullable
    private static BlockPos findFallbackLocation(
            int centerX, int centerZ, int worldRadius, int zoneSize,
            List<ForcedBiomeZone> existingZones, Random random,
            @Nullable DirectionalPlacement directional,
            BiomeClimateClassifier.TemperatureCategory targetTemp,
            BiomeClimateClassifier.HumidityCategory targetHumid,
            int maxAttempts) {

        // Get angle constraints if directional placement is active
        double minAngle = 0;
        double maxAngle = 2 * Math.PI;
        if (directional != null) {
            double[] range = directional.getConstrainedAngleRange(targetTemp, targetHumid);
            minAngle = range[0];
            maxAngle = range[1];
        }

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = minAngle + random.nextDouble() * (maxAngle - minAngle);
            double dist = (worldRadius * 0.3) + (random.nextDouble() * worldRadius * 0.6);
            int px = centerX + (int) (Math.cos(angle) * dist);
            int pz = centerZ + (int) (Math.sin(angle) * dist);

            if (!isValidPlacement(px, pz, centerX, centerZ, worldRadius, zoneSize, existingZones)) {
                continue;
            }

            // Double-check directional constraint (angle range is an approximation)
            if (directional != null && !directional.isInCorrectRegion(
                    px, pz, centerX, centerZ, worldRadius, targetTemp, targetHumid)) {
                continue;
            }

            return new BlockPos(px, 0, pz);
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
            double minDist = existingMaxRadius + maxRadius + 32; // 32 blocks padding
            double edx = px - existing.centerX();
            double edz = pz - existing.centerZ();
            if (Math.sqrt(edx * edx + edz * edz) < minDist) {
                return false;
            }
        }

        return true;
    }
}
