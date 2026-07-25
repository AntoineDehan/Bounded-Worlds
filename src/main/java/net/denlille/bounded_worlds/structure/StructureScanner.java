package net.denlille.bounded_worlds.structure;

import com.mojang.datafixers.util.Pair;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.denlille.bounded_worlds.biome.BiomeScanner;
import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.denlille.bounded_worlds.biome.ClimateMatcher;
import net.denlille.bounded_worlds.biome.ForcedBiomeZone;
import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.denlille.bounded_worlds.biome.ZoneClimateTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class StructureScanner {

    public static ScanResult scanAndPlace(ServerLevel level, int radius, List<String> structureIds,
                                           BiomeScanner.ScanResult biomeScanResult, BiomeZoneSize sizeCategory) {
        long startTime = System.currentTimeMillis();

        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BlockPos spawnPos = level.getSharedSpawnPos();
        long worldSeed = level.getSeed();
        Random random = new Random(worldSeed ^ 0xDEADBEEFL);

        List<String> found = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String idStr : structureIds) {
            ResourceLocation structureId = ResourceLocation.tryParse(idStr);
            if (structureId == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Invalid structure ID: {}", idStr);
                failed.add(idStr);
                continue;
            }

            ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, structureId);
            Holder.Reference<Structure> structureHolder = structureRegistry.getHolder(structureKey).orElse(null);
            if (structureHolder == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Structure not found in registry: {}. Available structures can be listed with /locate structure", idStr);
                failed.add(idStr);
                continue;
            }

            Structure structure = structureHolder.value();

            // Use vanilla /locate logic to find the structure
            BlockPos existingPos = locateStructureVanilla(level, structureHolder, spawnPos, radius);

            if (existingPos != null) {
                long dx = existingPos.getX() - spawnPos.getX();
                long dz = existingPos.getZ() - spawnPos.getZ();
                if (dx * dx + dz * dz <= (long) radius * radius) {
                    BoundedWorlds.LOGGER.info("[Bounded Worlds]   FOUND: {} at ({}, {})", idStr, existingPos.getX(), existingPos.getZ());
                    found.add(idStr);
                    continue;
                } else {
                    BoundedWorlds.LOGGER.info("[Bounded Worlds]   Nearest {} is at ({}, {}) but outside radius (dist={})",
                            idStr, existingPos.getX(), existingPos.getZ(),
                            (int) Math.sqrt(dx * dx + dz * dz));
                }
            }

            // Structure not found within radius — try to force-place it
            BoundedWorlds.LOGGER.info("[Bounded Worlds]   MISSING: {} — attempting force-placement...", idStr);

            HolderSet<Biome> validBiomes = structure.biomes();
            BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Structure {} requires biomes: {}", idStr, validBiomes);

            // Generate the StructureStart once and pass it to forcePlace
            GenerateResult generateResult = findValidPlacementAndGenerate(level, structure, biomeScanResult, spawnPos, radius, random, idStr);

            // Fallback: if no existing biome worked, force a dedicated biome zone and retry
            if (generateResult == null) {
                BoundedWorlds.LOGGER.info("[Bounded Worlds]   Primary placement failed for {} — trying fallback with forced biome zone...", idStr);
                generateResult = fallbackWithForcedBiome(level, structure, spawnPos, radius, random, idStr, sizeCategory, biomeScanResult);
            }

            if (generateResult == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds]   FAILED: Could not place {} even with fallback.", idStr);
                failed.add(idStr);
                continue;
            }

            boolean success = forcePlace(level, structure, generateResult, idStr);
            if (success) {
                BoundedWorlds.LOGGER.info("[Bounded Worlds]   PLACED: {} at ({}, {})", idStr,
                        generateResult.pos().getX(), generateResult.pos().getZ());
                placed.add(idStr);
            } else {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds]   FAILED: Could not write {} at ({}, {})", idStr,
                        generateResult.pos().getX(), generateResult.pos().getZ());
                failed.add(idStr);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new ScanResult(found, placed, failed, elapsed);
    }

    @Nullable
    private static BlockPos locateStructureVanilla(ServerLevel level, Holder<Structure> structureHolder,
                                                    BlockPos center, int radius) {
        int chunkSearchRadius = radius / 16;
        try {
            HolderSet<Structure> holderSet = HolderSet.direct(structureHolder);
            Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(
                            level,
                            holderSet,
                            center,
                            chunkSearchRadius,
                            false
                    );
            return result != null ? result.getFirst() : null;
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Error locating structure: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Finds a valid placement position AND generates the StructureStart in one pass.
     * This avoids the non-determinism risk of calling Structure.generate() twice.
     */
    @Nullable
    private static GenerateResult findValidPlacementAndGenerate(ServerLevel level, Structure structure,
                                                                 BiomeScanner.ScanResult biomeScanResult,
                                                                 BlockPos center, int radius, Random random,
                                                                 String structureId) {
        int centerX = center.getX();
        int centerZ = center.getZ();
        int borderPadding = 100;
        int effectiveRadius = radius - borderPadding;
        HolderSet<Biome> validBiomes = structure.biomes();

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = null;
        try {
            sampler = level.getChunkSource().randomState().sampler();
        } catch (Exception ignored) {}

        List<BlockPos> candidates = new ArrayList<>();
        for (Map.Entry<Holder<Biome>, BlockPos> entry : biomeScanResult.biomeLocations().entrySet()) {
            if (validBiomes.contains(entry.getKey())) {
                BlockPos pos = entry.getValue();

                Holder<Biome> biomeAtPos = entry.getKey();
                if (biomeAtPos.is(BiomeTags.IS_OCEAN) || biomeAtPos.is(BiomeTags.IS_RIVER) || biomeAtPos.is(BiomeTags.IS_DEEP_OCEAN)) {
                    continue;
                }

                if (sampler != null) {
                    Holder<Biome> actualBiome = biomeSource.getNoiseBiome(
                            pos.getX() >> 2, 64 >> 2, pos.getZ() >> 2, sampler);
                    if (actualBiome.is(BiomeTags.IS_OCEAN) || actualBiome.is(BiomeTags.IS_RIVER) || actualBiome.is(BiomeTags.IS_DEEP_OCEAN)) {
                        continue;
                    }
                }

                long dx = pos.getX() - centerX;
                long dz = pos.getZ() - centerZ;
                if (dx * dx + dz * dz <= (long) effectiveRadius * effectiveRadius) {
                    candidates.add(pos);
                }
            }
        }

        if (candidates.isEmpty()) {
            List<String> validBiomeNames = new ArrayList<>();
            validBiomes.forEach(holder -> holder.unwrapKey()
                    .map(ResourceKey::location)
                    .ifPresent(loc -> validBiomeNames.add(loc.toString())));
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   {} needs biomes: {}", structureId, validBiomeNames);
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   But none of those were found in the scanned area.");
            return null;
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds]   Found {} valid biome candidate(s) for {}", candidates.size(), structureId);

        candidates.sort((a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX()) : Integer.compare(a.getZ(), b.getZ()));
        java.util.Collections.shuffle(candidates, random);

        RandomState randomState = level.getChunkSource().randomState();
        int tried = 0;

        for (BlockPos candidate : candidates) {
            if (tried >= 20) break;
            tried++;

            ChunkPos chunkPos = new ChunkPos(candidate);
            try {
                StructureStart start = structure.generate(
                        level.registryAccess(),
                        level.getChunkSource().getGenerator(),
                        level.getChunkSource().getGenerator().getBiomeSource(),
                        randomState,
                        level.getStructureManager(),
                        level.getSeed(),
                        chunkPos,
                        0,
                        level,
                        holder -> true
                );
                if (start != null && start != StructureStart.INVALID_START) {
                    BoundedWorlds.LOGGER.info("[Bounded Worlds]   Structure.generate() succeeded at chunk ({}, {}) after {} attempt(s)",
                            chunkPos.x, chunkPos.z, tried);
                    return new GenerateResult(candidate, chunkPos, start);
                } else {
                    BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Structure.generate() returned INVALID at chunk ({}, {})",
                            chunkPos.x, chunkPos.z);
                }
            } catch (Exception e) {
                BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Structure.generate() threw at chunk ({}, {}): {}",
                        chunkPos.x, chunkPos.z, e.getMessage());
            }
        }

        BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Structure.generate() failed for all {} candidate(s) for {}",
                tried, structureId);
        return null;
    }

    /**
     * Fallback: creates a forced biome zone for the structure and retries generation.
     * Prefers the (biome, position) pair whose climate best matches the existing
     * terrain, like BiomeZonePlanner does; falls back to a seeded random choice
     * when no climate data is available.
     */
    @Nullable
    private static GenerateResult fallbackWithForcedBiome(ServerLevel level, Structure structure,
                                                           BlockPos center, int radius, Random random,
                                                           String structureId, BiomeZoneSize sizeCategory,
                                                           BiomeScanner.ScanResult biomeScanResult) {
        HolderSet<Biome> validBiomes = structure.biomes();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();

        // Pick a non-ocean/river biome from the structure's valid biomes
        List<Holder<Biome>> candidates = new ArrayList<>();
        validBiomes.forEach(holder -> {
            if (!holder.is(BiomeTags.IS_OCEAN) && !holder.is(BiomeTags.IS_RIVER) && !holder.is(BiomeTags.IS_DEEP_OCEAN)) {
                candidates.add(holder);
            }
        });

        if (candidates.isEmpty()) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Fallback: {} has no valid non-ocean biomes.", structureId);
            return null;
        }

        candidates.sort((a, b) -> {
            String nameA = a.unwrapKey().map(k -> k.location().toString()).orElse("");
            String nameB = b.unwrapKey().map(k -> k.location().toString()).orElse("");
            return nameA.compareTo(nameB);
        });

        // Generate a zone size large enough for a structure
        int zoneSize = Math.max(sizeCategory.randomSize(random), 250);

        int centerX = center.getX();
        int centerZ = center.getZ();
        int borderPadding = 100;
        int effectiveRadius = radius - borderPadding;
        double zoneMaxRadius = ForcedBiomeZone.maxFootprint(zoneSize); // noise distortion + morphing halo

        // Preferred path: the (biome, position) pair with the best climate match,
        // so the zone's terrain fits the biome (same logic as the biome planner)
        Holder<Biome> chosenBiome = null;
        BlockPos placement = null;
        long bestDist = Long.MAX_VALUE;
        for (Holder<Biome> member : candidates) {
            List<Climate.ParameterPoint> points = ClimateMatcher.getParameterPoints(member, biomeSource);
            if (points.isEmpty()) continue;
            ClimateMatcher.TerrainKind terrainKind = ClimateMatcher.targetTerrainKind(points);

            for (BiomeScanner.ClimateSample sample : biomeScanResult.climateSamples()) {
                if (!ClimateMatcher.matchesTerrainKind(sample.climate(), terrainKind)) continue;
                if (!isValidZonePlacement(sample.x(), sample.z(), centerX, centerZ, effectiveRadius, zoneMaxRadius)) continue;

                long dist = ClimateMatcher.bestDistanceSq(sample.climate(), points);
                if (dist < bestDist) {
                    bestDist = dist;
                    chosenBiome = member;
                    placement = new BlockPos(sample.x(), 0, sample.z());
                }
            }
        }

        // Fallback path: seeded random biome + random position (no climate data)
        if (chosenBiome == null) {
            chosenBiome = candidates.get(random.nextInt(candidates.size()));
            for (int attempt = 0; attempt < 50; attempt++) {
                double angle = random.nextDouble() * 2 * Math.PI;
                double dist = (effectiveRadius * 0.3) + (random.nextDouble() * effectiveRadius * 0.6);
                int px = centerX + (int) (Math.cos(angle) * dist);
                int pz = centerZ + (int) (Math.sin(angle) * dist);

                if (isValidZonePlacement(px, pz, centerX, centerZ, effectiveRadius, zoneMaxRadius)) {
                    placement = new BlockPos(px, 0, pz);
                    break;
                }
            }
        }

        String biomeName = chosenBiome.unwrapKey().map(k -> k.location().toString()).orElse("unknown");

        if (placement == null) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Fallback: could not find valid placement for biome zone.");
            return null;
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds]   Fallback: chose biome {} for structure {}", biomeName, structureId);

        // Create and register the forced biome zone, with a climate morph target
        // for smooth vanilla transitions at the edges (null = hard override only)
        // and terrain raising when the spot is underwater (structure biomes are
        // never oceanic — the candidates exclude ocean/river biomes above).
        ZoneClimateTarget morphTarget = null;
        ForcedBiomeZone.TerrainShaping terrainShaping = ForcedBiomeZone.TerrainShaping.NONE;
        try {
            Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
            morphTarget = ClimateMatcher.computeMorphTarget(
                    chosenBiome, biomeSource, sampler, placement.getX(), placement.getZ());
            if (ClimateMatcher.isOceanicSample(sampler.sample(placement.getX() >> 2, 64 >> 2, placement.getZ() >> 2))) {
                terrainShaping = ForcedBiomeZone.TerrainShaping.RAISE_ISLAND;
            }
        } catch (Exception e) {
            BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Fallback: no climate morph target available: {}", e.getMessage());
        }

        String desc = biomeName + " (fallback for " + structureId + ")";
        ForcedBiomeZone zone = new ForcedBiomeZone(placement.getX(), placement.getZ(), zoneSize,
                chosenBiome, desc, morphTarget, terrainShaping);
        ForcedBiomeZoneManager.addZone(zone);

        BoundedWorlds.LOGGER.info("[Bounded Worlds]   Fallback: created forced biome zone {} at ({}, {}), size {}",
                desc, placement.getX(), placement.getZ(), zoneSize);

        // Try Structure.generate() at the center of the new zone
        RandomState randomState = level.getChunkSource().randomState();
        ChunkPos chunkPos = new ChunkPos(placement);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos tryPos = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
                try {
                    StructureStart start = structure.generate(
                            level.registryAccess(),
                            level.getChunkSource().getGenerator(),
                            level.getChunkSource().getGenerator().getBiomeSource(),
                            randomState,
                            level.getStructureManager(),
                            level.getSeed(),
                            tryPos,
                            0,
                            level,
                            holder -> true
                    );
                    if (start != null && start != StructureStart.INVALID_START) {
                        BlockPos resultPos = new BlockPos(tryPos.getMinBlockX(), 0, tryPos.getMinBlockZ());
                        BoundedWorlds.LOGGER.info("[Bounded Worlds]   Fallback: Structure.generate() succeeded at chunk ({}, {})",
                                tryPos.x, tryPos.z);
                        return new GenerateResult(resultPos, tryPos, start);
                    }
                } catch (Exception e) {
                    BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Fallback: Structure.generate() threw at chunk ({}, {}): {}",
                            tryPos.x, tryPos.z, e.getMessage());
                }
            }
        }

        BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Fallback: Structure.generate() failed even in forced biome zone for {}", structureId);
        return null;
    }

    /**
     * Zone placement validity for the structure fallback: fits within the
     * effective radius and does not overlap any registered forced zone.
     */
    private static boolean isValidZonePlacement(int px, int pz, int centerX, int centerZ,
                                                 int effectiveRadius, double zoneMaxRadius) {
        double distFromCenter = Math.sqrt((long)(px - centerX) * (px - centerX) + (long)(pz - centerZ) * (pz - centerZ));
        if (distFromCenter + zoneMaxRadius > effectiveRadius) {
            return false;
        }

        for (ForcedBiomeZone existing : ForcedBiomeZoneManager.getZones()) {
            double minDist = existing.maxFootprint() + zoneMaxRadius + 32;
            double edx = px - existing.centerX();
            double edz = pz - existing.centerZ();
            if (Math.sqrt(edx * edx + edz * edz) < minDist) {
                return false;
            }
        }
        return true;
    }

    private static boolean forcePlace(ServerLevel level, Structure structure, GenerateResult result, String structureId) {
        ChunkPos chunkPos = result.chunkPos();
        StructureStart start = result.start();

        try {
            BoundedWorlds.LOGGER.info("[Bounded Worlds]   forcePlace: StructureStart has {} piece(s), bounding box: {}",
                    start.getPieces().size(), start.getBoundingBox());

            ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS, true);
            if (chunk == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds]   forcePlace: Could not get chunk at ({}, {})", chunkPos.x, chunkPos.z);
                return false;
            }

            chunk.setStartForStructure(structure, start);

            BoundingBox bb = start.getBoundingBox();
            int minCX = SectionPos.blockToSectionCoord(bb.minX());
            int minCZ = SectionPos.blockToSectionCoord(bb.minZ());
            int maxCX = SectionPos.blockToSectionCoord(bb.maxX());
            int maxCZ = SectionPos.blockToSectionCoord(bb.maxZ());

            BoundedWorlds.LOGGER.info("[Bounded Worlds]   forcePlace: Adding references for chunks ({},{}) to ({},{})",
                    minCX, minCZ, maxCX, maxCZ);

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    ChunkAccess refChunk = level.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, true);
                    if (refChunk != null) {
                        refChunk.addReferenceForStructure(structure, chunkPos.toLong());
                    }
                }
            }

            BoundedWorlds.LOGGER.info("[Bounded Worlds]   forcePlace: StructureStart written. Structure will generate when chunks are loaded by players.");
            return true;
        } catch (Exception e) {
            BoundedWorlds.LOGGER.error("[Bounded Worlds]   forcePlace: Exception for {} at ({}, {})",
                    structureId, result.pos().getX(), result.pos().getZ(), e);
            return false;
        }
    }

    private record GenerateResult(BlockPos pos, ChunkPos chunkPos, StructureStart start) {}

    public record ScanResult(
            List<String> found,
            List<String> placed,
            List<String> failed,
            long scanTimeMs
    ) {}
}
