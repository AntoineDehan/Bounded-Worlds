package net.denlille.bounded_worlds.structure;

import com.mojang.datafixers.util.Pair;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.denlille.bounded_worlds.biome.BiomeScanner;
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
                                           BiomeScanner.ScanResult biomeScanResult) {
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

            // I1 fix: generate StructureStart once and pass it to forcePlace
            GenerateResult generateResult = findValidPlacementAndGenerate(level, structure, biomeScanResult, spawnPos, radius, random, idStr);
            if (generateResult == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds]   FAILED: Could not find valid biome location for {}", idStr);
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
