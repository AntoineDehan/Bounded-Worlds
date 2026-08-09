package net.denlille.bounded_worlds.event;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.denlille.bounded_worlds.biome.BiomeRequirement;
import net.denlille.bounded_worlds.biome.BiomeScanner;
import net.denlille.bounded_worlds.biome.BiomeZonePlanner;
import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.denlille.bounded_worlds.biome.DirectionalClimateManager;
import net.denlille.bounded_worlds.biome.DirectionalPlacement;
import net.denlille.bounded_worlds.biome.ForcedBiomeZone;
import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.denlille.bounded_worlds.biome.ZonePersistence;
import net.denlille.bounded_worlds.config.CompassDirection;
import net.denlille.bounded_worlds.config.DimensionRequirementsConfig;
import net.denlille.bounded_worlds.config.ModConfigs;
import net.denlille.bounded_worlds.structure.StructureScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class WorldBorderHandler {

    private static final String MARKER_FILE = "bounded_worlds_initialized.dat";

    /** A configured modded/datapack dimension, resolved and registered. */
    private record CustomDimension(ServerLevel level, ForcedBiomeZoneManager.DimensionEntry entry,
                                   List<String> biomes) {}

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }

        int radius = ModConfigs.WORLD_RADIUS.get();
        BlockPos spawnPos = overworld.getSharedSpawnPos();

        // Check if this is the first time the mod runs on this world
        Path worldDir = event.getServer().getWorldPath(LevelResource.ROOT);
        Path markerPath = worldDir.resolve(MARKER_FILE);
        boolean firstRun = !Files.exists(markerPath);

        // Phase 1: World border (only on first run)
        if (firstRun) {
            WorldBorder worldBorder = overworld.getWorldBorder();
            worldBorder.setCenter(spawnPos.getX(), spawnPos.getZ());
            worldBorder.setSize(radius * 2.0);

            BoundedWorlds.LOGGER.info("[Bounded Worlds] World border set to {} blocks radius around spawn ({}, {})",
                    radius, spawnPos.getX(), spawnPos.getZ());
        } else {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] World already initialized, skipping world border setup.");
        }

        // Build directional placement if enabled
        DirectionalPlacement directional = buildDirectionalPlacement(overworld.getSeed());

        // Initialize directional climate bias if enabled
        DirectionalClimateManager.clear();
        if (directional != null) {
            DirectionalClimateManager.init(directional, spawnPos.getX(), spawnPos.getZ(), radius);
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Directional climate bias initialized.");
        }

        // Phase 2: Biome guarantee — per-dimension state. The mixins resolve
        // their dimension by identity lookup, so nothing leaks into
        // unregistered dimensions (the End, modded dims).
        ForcedBiomeZoneManager.clear();
        ForcedBiomeZoneManager.setTerrainShapingEnabled(ModConfigs.TERRAIN_SHAPING.get());
        ForcedBiomeZoneManager.DimensionEntry overworldEntry = ForcedBiomeZoneManager.register(overworld);
        ServerLevel nether = event.getServer().getLevel(Level.NETHER);
        ForcedBiomeZoneManager.DimensionEntry netherEntry = nether != null
                ? ForcedBiomeZoneManager.register(nether) : null;

        // Custom (modded/datapack) dimensions from bounded_worlds-dimensions.json.
        // Their entries must exist before persisted zones are routed below,
        // otherwise their zones would be dropped as "unknown dimension".
        List<CustomDimension> customDimensions = new ArrayList<>();
        for (Map.Entry<ResourceLocation, List<String>> dimReq : DimensionRequirementsConfig.load().entrySet()) {
            ResourceLocation dimensionId = dimReq.getKey();

            if (dimensionId.equals(Level.OVERWORLD.location()) || dimensionId.equals(Level.NETHER.location())) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] {} in {} — use the [world] / [nether] sections " +
                        "of bounded_worlds-common.toml instead; entry skipped.",
                        dimensionId, DimensionRequirementsConfig.FILE_NAME);
                continue;
            }

            ServerLevel dimLevel = event.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            if (dimLevel == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Dimension {} not found — is the mod/datapack installed? Entry skipped.",
                        dimensionId);
                continue;
            }

            BiomeSource dimBiomeSource = dimLevel.getChunkSource().getGenerator().getBiomeSource();
            if (!(dimBiomeSource instanceof MultiNoiseBiomeSource)) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Dimension {} uses a custom biome source ({}) — " +
                        "biome forcing is not supported there; entry skipped.",
                        dimensionId, dimBiomeSource.getClass().getName());
                continue;
            }

            customDimensions.add(new CustomDimension(dimLevel, ForcedBiomeZoneManager.register(dimLevel), dimReq.getValue()));
        }

        // Load zones persisted in a previous session and route them to their
        // dimension — the scans below see them through the mixins, so their
        // requirements count as satisfied and only genuinely new requirements
        // get fresh zones planned.
        Path zonesPath = worldDir.resolve(ZonePersistence.ZONES_FILE);
        Registry<Biome> biomeRegistry = overworld.registryAccess().registryOrThrow(Registries.BIOME);
        ZonePersistence.load(zonesPath, biomeRegistry).forEach((dimensionId, zones) -> {
            ForcedBiomeZoneManager.DimensionEntry target = null;
            for (ForcedBiomeZoneManager.DimensionEntry entry : ForcedBiomeZoneManager.entries()) {
                if (entry.dimensionId().equals(dimensionId)) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Dropping {} persisted zone(s) for unknown dimension {}.",
                        zones.size(), dimensionId);
                return;
            }
            zones.forEach(target::addZone);
        });

        BiomeScanner.ScanResult biomeScanResult = handleBiomePhase(
                overworld, spawnPos, radius, directional, overworldEntry, ModConfigs.REQUIRED_BIOMES.get());

        // Nether biome guarantee. No custom Nether border: vanilla shares one
        // border across dimensions and the client always renders the overworld
        // one (PlayerList.sendLevelInfo), so a different server-side Nether
        // border would be an invisible, restart-fragile wall. The mirrored
        // vanilla border already bounds the Nether at the same numeric radius,
        // and portal placement is clamped to the border — no escape possible.
        // Zones are planned within radius/8 of the scaled spawn so required
        // biomes stay reachable in the area portals actually use.
        if (netherEntry != null) {
            int netherRadius = Math.min(radius, Math.max(200, radius / 8));
            BlockPos netherCenter = new BlockPos(spawnPos.getX() / 8, 0, spawnPos.getZ() / 8);
            handleBiomePhase(nether, netherCenter, netherRadius, null, netherEntry, ModConfigs.NETHER_REQUIRED_BIOMES.get());
        }

        // Custom dimension biome guarantees — full radius around the shared
        // spawn coordinates (no portal-scaling convention for modded dims).
        for (CustomDimension custom : customDimensions) {
            handleBiomePhase(custom.level(), spawnPos, radius, null, custom.entry(), custom.biomes());
        }

        // Phase 3: Structure guarantee (only on first run — structures are permanent)
        if (firstRun) {
            handleStructurePhase(overworld, radius, biomeScanResult);

            // Write marker file
            try {
                Files.createDirectories(markerPath.getParent());
                Files.writeString(markerPath, "Bounded Worlds initialized. Delete this file to re-run border and structure setup. " +
                        "Delete " + ZonePersistence.ZONES_FILE + " as well to re-plan forced biome zones from scratch.");
                BoundedWorlds.LOGGER.info("[Bounded Worlds] First-run setup complete. Marker file written.");
            } catch (IOException e) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not write marker file: {}", e.getMessage());
            }
        }

        // Persist all zones (biome guarantee + structure fallbacks) so they
        // survive restarts, config edits and placement-algorithm changes.
        boolean hasZones = false;
        for (ForcedBiomeZoneManager.DimensionEntry entry : ForcedBiomeZoneManager.entries()) {
            if (!entry.zones().isEmpty()) {
                hasZones = true;
                break;
            }
        }
        if (hasZones) {
            ZonePersistence.save(zonesPath, ForcedBiomeZoneManager.entries());
        }
    }

    @javax.annotation.Nullable
    private DirectionalPlacement buildDirectionalPlacement(long worldSeed) {
        if (!ModConfigs.DIRECTIONAL_ENABLED.get()) {
            return null;
        }

        Random seedRandom = new Random(worldSeed ^ 0xD1EC710AL);

        // Resolve RANDOM directions using the world seed
        CompassDirection hotDir = ModConfigs.HOT_DIRECTION.get().resolve(seedRandom);
        CompassDirection humidDir = ModConfigs.HUMID_DIRECTION.get().resolvePerpendicularTo(hotDir, seedRandom);

        // Validate perpendicularity
        if (!hotDir.isPerpendicularTo(humidDir)) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] hotDirection ({}) and humidDirection ({}) are not perpendicular! Falling back to SOUTH/EAST.",
                    hotDir, humidDir);
            hotDir = CompassDirection.SOUTH;
            humidDir = CompassDirection.EAST;
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Directional placement enabled: hot={}, cold={}, humid={}, dry={}",
                hotDir, hotDir.opposite(), humidDir, humidDir.opposite());

        return new DirectionalPlacement(hotDir, humidDir);
    }

    private BiomeScanner.ScanResult handleBiomePhase(ServerLevel level, BlockPos center, int radius,
                                                      @javax.annotation.Nullable DirectionalPlacement directional,
                                                      ForcedBiomeZoneManager.DimensionEntry dimensionEntry,
                                                      List<? extends String> biomeEntries) {
        String dimensionName = level.dimension().location().toString();

        List<BiomeRequirement> requirements = new ArrayList<>();
        for (String entry : biomeEntries) {
            BiomeRequirement req = BiomeRequirement.parse(entry);
            if (req != null) {
                requirements.add(req);
            }
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Scanning {} biomes within {} block radius of ({}, {})...",
                dimensionName, radius, center.getX(), center.getZ());
        BiomeScanner.ScanResult result = BiomeScanner.scan(level, center, radius, requirements);

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Biome scan complete in {}ms. Sampled {} points, found {} unique biomes.",
                result.scanTimeMs(), result.sampledPoints(), result.foundBiomeIds().size());

        if (requirements.isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] No required biomes configured for {}.", dimensionName);
            return result;
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Biomes found: {}", result.foundBiomeIds());

        for (BiomeRequirement req : result.satisfied()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds]   FOUND: {}", req.description());
        }
        for (BiomeRequirement req : result.missing()) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds]   MISSING: {}", req.description());
        }

        if (result.missing().isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] All {} required biomes/tags are present in {}!",
                    requirements.size(), dimensionName);
            return result;
        }

        BoundedWorlds.LOGGER.warn("[Bounded Worlds] {} of {} required biomes/tags are MISSING in {}.",
                result.missing().size(), requirements.size(), dimensionName);

        // Phase 2b: Force-place missing biomes
        BiomeZoneSize sizeCategory = ModConfigs.FORCED_BIOME_SIZE.get();

        List<ForcedBiomeZone> zones = BiomeZonePlanner.planZones(
                level, center, result.missing(), result, radius, sizeCategory, directional, dimensionEntry.zones());

        for (ForcedBiomeZone zone : zones) {
            dimensionEntry.addZone(zone);
        }

        if (!zones.isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Registered {} forced biome zone(s).", zones.size());

            // Enrich the scan result with the forced zones so StructureScanner can use them
            // (ScanResult is immutable — we build a merged copy instead of mutating it)
            Map<Holder<Biome>, BlockPos> zoneLocations = new HashMap<>();
            for (ForcedBiomeZone zone : zones) {
                BlockPos zonePos = new BlockPos(zone.centerX(), 64, zone.centerZ());
                zoneLocations.putIfAbsent(zone.biome(), zonePos);
            }
            result = result.withAdditionalBiomeLocations(zoneLocations);
        }

        return result;
    }

    private void handleStructurePhase(ServerLevel overworld, int radius, BiomeScanner.ScanResult biomeScanResult) {
        List<? extends String> structureEntries = ModConfigs.REQUIRED_STRUCTURES.get();
        if (structureEntries.isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] No required structures configured, skipping structure scan.");
            return;
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Checking {} required structure(s) within {} block radius...",
                structureEntries.size(), radius);

        BiomeZoneSize sizeCategory = ModConfigs.FORCED_BIOME_SIZE.get();
        List<String> structureIds = new ArrayList<>(structureEntries);
        StructureScanner.ScanResult result = StructureScanner.scanAndPlace(overworld, radius, structureIds, biomeScanResult, sizeCategory);

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Structure scan complete in {}ms. Found: {}, Placed: {}, Failed: {}",
                result.scanTimeMs(), result.found().size(), result.placed().size(), result.failed().size());

        if (!result.failed().isEmpty()) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Failed to place structures: {}", result.failed());
        }
    }
}
