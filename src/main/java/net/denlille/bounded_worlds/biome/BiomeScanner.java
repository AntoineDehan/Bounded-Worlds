package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BiomeScanner {

    // 16-block step balances scan time and accuracy (biome resolution is 4 blocks)
    private static final int SAMPLE_STEP = 16;
    // Scan both surface and underground to catch cave biomes
    private static final int[] SAMPLE_Y_LEVELS = {64, -32};
    // Coarser grid of climate samples used as zone placement candidates (~7k points at radius 3000)
    private static final int CANDIDATE_STEP = 64;
    private static final int CANDIDATE_Y = 64;

    public static ScanResult scan(ServerLevel level, int radius, List<BiomeRequirement> requirements) {
        long startTime = System.currentTimeMillis();

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        BlockPos spawnPos = level.getSharedSpawnPos();
        int centerX = spawnPos.getX();
        int centerZ = spawnPos.getZ();

        Set<Holder<Biome>> foundBiomes = new HashSet<>();
        Map<Holder<Biome>, BlockPos> biomeLocations = new HashMap<>();
        List<ClimateSample> climateSamples = new ArrayList<>();
        int sampledPoints = 0;

        Climate.Sampler sampler;
        try {
            sampler = level.getChunkSource().randomState().sampler();
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not obtain Climate.Sampler (flat world or custom generator?). " +
                    "Falling back to possibleBiomes().");
            foundBiomes.addAll(biomeSource.possibleBiomes());
            return buildResult(foundBiomes, biomeLocations, climateSamples, requirements, 0, startTime);
        }

        for (int x = centerX - radius; x <= centerX + radius; x += SAMPLE_STEP) {
            boolean candidateX = ((x - (centerX - radius)) % CANDIDATE_STEP) == 0;
            for (int z = centerZ - radius; z <= centerZ + radius; z += SAMPLE_STEP) {
                long dx = x - centerX;
                long dz = z - centerZ;
                if (dx * dx + dz * dz > (long) radius * radius) {
                    continue;
                }

                for (int sampleY : SAMPLE_Y_LEVELS) {
                    Holder<Biome> biome = biomeSource.getNoiseBiome(
                            x >> 2, sampleY >> 2, z >> 2, sampler
                    );
                    if (foundBiomes.add(biome)) {
                        biomeLocations.put(biome, new BlockPos(x, sampleY, z));
                    }
                }

                // Coarser grid: record the raw climate as a placement candidate
                if (candidateX && ((z - (centerZ - radius)) % CANDIDATE_STEP) == 0) {
                    Climate.TargetPoint climate = sampler.sample(x >> 2, CANDIDATE_Y >> 2, z >> 2);
                    climateSamples.add(new ClimateSample(x, z, climate));
                }
                sampledPoints++;
            }
        }

        return buildResult(foundBiomes, biomeLocations, climateSamples, requirements, sampledPoints, startTime);
    }

    private static ScanResult buildResult(Set<Holder<Biome>> foundBiomes,
                                           Map<Holder<Biome>, BlockPos> biomeLocations,
                                           List<ClimateSample> climateSamples,
                                           List<BiomeRequirement> requirements,
                                           int sampledPoints, long startTime) {
        Set<ResourceLocation> foundBiomeIds = new HashSet<>();
        for (Holder<Biome> biome : foundBiomes) {
            biome.unwrapKey()
                    .map(ResourceKey::location)
                    .ifPresent(foundBiomeIds::add);
        }

        List<BiomeRequirement> satisfied = new ArrayList<>();
        List<BiomeRequirement> missing = new ArrayList<>();
        for (BiomeRequirement req : requirements) {
            if (req.isSatisfiedByAny(foundBiomes)) {
                satisfied.add(req);
            } else {
                missing.add(req);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new ScanResult(foundBiomeIds, biomeLocations, satisfied, missing, climateSamples, sampledPoints, elapsed);
    }

    /**
     * A placement candidate: the raw climate sampled at a fixed position on the
     * coarse candidate grid.
     */
    public record ClimateSample(int x, int z, Climate.TargetPoint climate) {}

    public record ScanResult(
            Set<ResourceLocation> foundBiomeIds,
            Map<Holder<Biome>, BlockPos> biomeLocations,
            List<BiomeRequirement> satisfied,
            List<BiomeRequirement> missing,
            List<ClimateSample> climateSamples,
            int sampledPoints,
            long scanTimeMs
    ) {
        // Defensive copies: this record is passed across the biome and structure
        // phases and must never be mutated after creation.
        public ScanResult {
            foundBiomeIds = Set.copyOf(foundBiomeIds);
            biomeLocations = Map.copyOf(biomeLocations);
            satisfied = List.copyOf(satisfied);
            missing = List.copyOf(missing);
            climateSamples = List.copyOf(climateSamples);
        }

        /**
         * Returns a copy with the given biome locations merged in (existing
         * entries win). Replaces the old pattern of mutating biomeLocations()
         * in place, which broke the record's immutability contract.
         */
        public ScanResult withAdditionalBiomeLocations(Map<Holder<Biome>, BlockPos> additional) {
            Map<Holder<Biome>, BlockPos> merged = new HashMap<>(biomeLocations);
            additional.forEach(merged::putIfAbsent);
            return new ScanResult(foundBiomeIds, merged, satisfied, missing, climateSamples, sampledPoints, scanTimeMs);
        }
    }
}
