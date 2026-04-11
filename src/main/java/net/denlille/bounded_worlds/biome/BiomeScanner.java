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

    // I2 fix: reduced from 64 to 16 for better accuracy (matches biome resolution of 4 blocks)
    private static final int SAMPLE_STEP = 16;
    // S4 fix: scan at both surface and underground
    private static final int[] SAMPLE_Y_LEVELS = {64, -32};

    public static ScanResult scan(ServerLevel level, int radius, List<BiomeRequirement> requirements) {
        long startTime = System.currentTimeMillis();

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        BlockPos spawnPos = level.getSharedSpawnPos();
        int centerX = spawnPos.getX();
        int centerZ = spawnPos.getZ();

        Set<Holder<Biome>> foundBiomes = new HashSet<>();
        Map<Holder<Biome>, BlockPos> biomeLocations = new HashMap<>();
        int sampledPoints = 0;

        Climate.Sampler sampler;
        try {
            sampler = level.getChunkSource().randomState().sampler();
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not obtain Climate.Sampler (flat world or custom generator?). " +
                    "Falling back to possibleBiomes().");
            foundBiomes.addAll(biomeSource.possibleBiomes());
            return buildResult(foundBiomes, biomeLocations, requirements, 0, startTime);
        }

        for (int x = centerX - radius; x <= centerX + radius; x += SAMPLE_STEP) {
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
                sampledPoints++;
            }
        }

        return buildResult(foundBiomes, biomeLocations, requirements, sampledPoints, startTime);
    }

    private static ScanResult buildResult(Set<Holder<Biome>> foundBiomes,
                                           Map<Holder<Biome>, BlockPos> biomeLocations,
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
        return new ScanResult(foundBiomeIds, biomeLocations, satisfied, missing, sampledPoints, elapsed);
    }

    public record ScanResult(
            Set<ResourceLocation> foundBiomeIds,
            Map<Holder<Biome>, BlockPos> biomeLocations,
            List<BiomeRequirement> satisfied,
            List<BiomeRequirement> missing,
            int sampledPoints,
            long scanTimeMs
    ) {}
}
