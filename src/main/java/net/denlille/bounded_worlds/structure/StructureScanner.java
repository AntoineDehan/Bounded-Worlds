package net.denlille.bounded_worlds.structure;

import com.mojang.datafixers.util.Pair;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.denlille.bounded_worlds.biome.BiomeScanner;
import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.denlille.bounded_worlds.biome.ClimateMatcher;
import net.denlille.bounded_worlds.biome.ForcedBiomeZone;
import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.denlille.bounded_worlds.biome.ZoneClimateTarget;
import net.denlille.bounded_worlds.config.RequirementsConfig;
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
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class StructureScanner {

    public static ScanResult scanAndPlace(ServerLevel level, int radius,
                                           List<RequirementsConfig.StructureRequirement> requirements,
                                           BiomeScanner.ScanResult biomeScanResult, BiomeZoneSize sizeCategory) {
        long startTime = System.currentTimeMillis();

        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BlockPos spawnPos = level.getSharedSpawnPos();
        long worldSeed = level.getSeed();
        Random random = new Random(worldSeed ^ 0xDEADBEEFL);

        List<String> found = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // Chunks already claimed by a force-placement this scan, so several
        // instances of the same structure never stack on one spot.
        Set<Long> usedChunks = new HashSet<>();

        for (RequirementsConfig.StructureRequirement requirement : requirements) {
            String idStr = requirement.id();
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

            if (requirement.hasMax()) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds]   {}: \"max\" is not enforced yet (planned for a future version) — " +
                        "only \"min\" ({}) is guaranteed.", idStr, requirement.min());
            }
            if (requirement.min() <= 0) {
                continue; // nothing to guarantee (min 0 only becomes meaningful with max)
            }

            Structure structure = structureHolder.value();

            int existing = countExisting(level, structureHolder, spawnPos, radius, requirement.min());
            if (existing >= requirement.min()) {
                BoundedWorlds.LOGGER.info("[Bounded Worlds]   FOUND: {} ({}/{} within radius)",
                        idStr, existing, requirement.min());
                found.add(idStr + " (" + existing + "/" + requirement.min() + ")");
                continue;
            }

            int deficit = requirement.min() - existing;
            BoundedWorlds.LOGGER.info("[Bounded Worlds]   MISSING: {} ({}/{} within radius) — force-placing {} instance(s)...",
                    idStr, existing, requirement.min(), deficit);
            BoundedWorlds.LOGGER.debug("[Bounded Worlds]   Structure {} requires biomes: {}", idStr, structure.biomes());

            int placedCount = 0;
            for (int i = 0; i < deficit; i++) {
                // Generate the StructureStart once and pass it to forcePlace
                GenerateResult generateResult = findValidPlacementAndGenerate(
                        level, structure, biomeScanResult, spawnPos, radius, random, idStr, usedChunks);

                // Fallback: if no existing biome worked, force a dedicated biome zone and retry
                if (generateResult == null) {
                    BoundedWorlds.LOGGER.info("[Bounded Worlds]   Primary placement failed for {} — trying fallback with forced biome zone...", idStr);
                    generateResult = fallbackWithForcedBiome(level, structure, spawnPos, radius, random, idStr,
                            sizeCategory, biomeScanResult, usedChunks);
                }

                if (generateResult == null) {
                    BoundedWorlds.LOGGER.warn("[Bounded Worlds]   FAILED: Could not place {} even with fallback ({} of {} placed).",
                            idStr, placedCount, deficit);
                    break;
                }

                if (forcePlace(level, structure, generateResult, idStr)) {
                    BoundedWorlds.LOGGER.info("[Bounded Worlds]   PLACED: {} at ({}, {})", idStr,
                            generateResult.pos().getX(), generateResult.pos().getZ());
                    usedChunks.add(generateResult.chunkPos().toLong());
                    placedCount++;
                } else {
                    BoundedWorlds.LOGGER.warn("[Bounded Worlds]   FAILED: Could not write {} at ({}, {})", idStr,
                            generateResult.pos().getX(), generateResult.pos().getZ());
                    break;
                }
            }

            if (placedCount > 0) {
                placed.add(idStr + " x" + placedCount);
            }
            if (placedCount < deficit) {
                failed.add(idStr + " (" + (existing + placedCount) + "/" + requirement.min() + ")");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new ScanResult(found, placed, failed, elapsed);
    }

    /**
     * Counts how many instances of a structure would exist within the radius,
     * stopping at {@code enough}. The bounded world makes this exact: every
     * natural placement cell is enumerated and checked with the same logic
     * /locate uses (works on ungenerated chunks — no chunk loading).
     */
    private static int countExisting(ServerLevel level, Holder<Structure> holder,
                                     BlockPos center, int radius, int enough) {
        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
        List<StructurePlacement> placements = structureState.getPlacementsForStructure(holder);
        if (placements.isEmpty()) {
            return 0; // no natural generation for this structure in this dimension
        }

        Structure structure = holder.value();
        StructureManager structureManager = level.structureManager();
        int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        int chunkRadius = radius / 16;
        long radiusSq = (long) radius * radius;
        Set<Long> countedChunks = new HashSet<>();
        int count = 0;

        for (StructurePlacement placement : placements) {
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int spacing = spread.spacing();
                int minRegionX = Math.floorDiv(centerChunkX - chunkRadius, spacing);
                int maxRegionX = Math.floorDiv(centerChunkX + chunkRadius, spacing);
                int minRegionZ = Math.floorDiv(centerChunkZ - chunkRadius, spacing);
                int maxRegionZ = Math.floorDiv(centerChunkZ + chunkRadius, spacing);
                for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                    for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                        ChunkPos candidate = spread.getPotentialStructureChunk(
                                structureState.getLevelSeed(), regionX * spacing, regionZ * spacing);
                        if (countsAsPresent(candidate, placement, structureState, structureManager, structure,
                                center, radiusSq, countedChunks)) {
                            count++;
                            if (count >= enough) return count;
                        }
                    }
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> ringPositions = structureState.getRingPositionsFor(rings);
                if (ringPositions == null) continue;
                for (ChunkPos candidate : ringPositions) {
                    if (countsAsPresent(candidate, placement, structureState, structureManager, structure,
                            center, radiusSq, countedChunks)) {
                        count++;
                        if (count >= enough) return count;
                    }
                }
            } else {
                // Modded placement type — no cell enumeration available; the
                // vanilla nearest-lookup can at least detect one instance.
                BlockPos nearest = locateStructureVanilla(level, holder, center, radius);
                if (nearest != null) {
                    long dx = nearest.getX() - center.getX();
                    long dz = nearest.getZ() - center.getZ();
                    if (dx * dx + dz * dz <= radiusSq) {
                        count++;
                        if (count >= enough) return count;
                    }
                }
                if (enough > 1) {
                    BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Placement type {} is not enumerable — " +
                            "at most one existing instance can be detected; the rest will be force-placed.",
                            placement.getClass().getSimpleName());
                }
            }
        }
        return count;
    }

    /** Radius + frequency + would-generate check for one candidate placement chunk. */
    private static boolean countsAsPresent(ChunkPos candidate, StructurePlacement placement,
                                           ChunkGeneratorStructureState structureState,
                                           StructureManager structureManager, Structure structure,
                                           BlockPos center, long radiusSq, Set<Long> countedChunks) {
        long dx = candidate.getMiddleBlockX() - center.getX();
        long dz = candidate.getMiddleBlockZ() - center.getZ();
        if (dx * dx + dz * dz > radiusSq) return false;
        if (!placement.isStructureChunk(structureState, candidate.x, candidate.z)) return false;
        if (!countedChunks.add(candidate.toLong())) return false;
        // START_PRESENT: would generate (or already has). CHUNK_LOAD_NEEDED: the
        // chunk was generated before this scan — the start was decided by the
        // same placement logic, so count it rather than force-loading the chunk.
        return structureManager.checkStructurePresence(candidate, structure, false)
                != StructureCheckResult.START_NOT_PRESENT;
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
                                                                 String structureId, Set<Long> usedChunks) {
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

            ChunkPos chunkPos = new ChunkPos(candidate);
            if (tooCloseToUsed(chunkPos, usedChunks)) {
                continue; // already claimed by a previous force-placement
            }
            tried++;

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
                                                           BiomeScanner.ScanResult biomeScanResult,
                                                           Set<Long> usedChunks) {
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

        ForcedBiomeZoneManager.DimensionEntry dimensionEntry = ForcedBiomeZoneManager.entryFor(level);
        if (dimensionEntry == null) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   Fallback: no zone registry for dimension {}.",
                    level.dimension().location());
            return null;
        }

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
                if (!isValidZonePlacement(sample.x(), sample.z(), centerX, centerZ, effectiveRadius, zoneMaxRadius, dimensionEntry)) continue;

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

                if (isValidZonePlacement(px, pz, centerX, centerZ, effectiveRadius, zoneMaxRadius, dimensionEntry)) {
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
        dimensionEntry.addZone(zone);

        BoundedWorlds.LOGGER.info("[Bounded Worlds]   Fallback: created forced biome zone {} at ({}, {}), size {}",
                desc, placement.getX(), placement.getZ(), zoneSize);

        // Try Structure.generate() at the center of the new zone
        RandomState randomState = level.getChunkSource().randomState();
        ChunkPos chunkPos = new ChunkPos(placement);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos tryPos = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
                if (tooCloseToUsed(tryPos, usedChunks)) {
                    continue;
                }
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

    /** True when a chunk is within 8 chunks of one already claimed this scan. */
    private static boolean tooCloseToUsed(ChunkPos pos, Set<Long> usedChunks) {
        for (long used : usedChunks) {
            ChunkPos usedPos = new ChunkPos(used);
            if (Math.max(Math.abs(usedPos.x - pos.x), Math.abs(usedPos.z - pos.z)) < 8) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zone placement validity for the structure fallback: fits within the
     * effective radius and does not overlap any registered forced zone.
     */
    private static boolean isValidZonePlacement(int px, int pz, int centerX, int centerZ,
                                                 int effectiveRadius, double zoneMaxRadius,
                                                 ForcedBiomeZoneManager.DimensionEntry dimensionEntry) {
        double distFromCenter = Math.sqrt((long)(px - centerX) * (px - centerX) + (long)(pz - centerZ) * (pz - centerZ));
        if (distFromCenter + zoneMaxRadius > effectiveRadius) {
            return false;
        }

        for (ForcedBiomeZone existing : dimensionEntry.zones()) {
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
