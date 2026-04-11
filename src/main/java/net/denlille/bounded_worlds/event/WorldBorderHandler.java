package net.denlille.bounded_worlds.event;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.denlille.bounded_worlds.biome.BiomeRequirement;
import net.denlille.bounded_worlds.biome.BiomeScanner;
import net.denlille.bounded_worlds.biome.BiomeZonePlanner;
import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.denlille.bounded_worlds.biome.ForcedBiomeZone;
import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.denlille.bounded_worlds.config.ModConfigs;
import net.denlille.bounded_worlds.structure.StructureScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorldBorderHandler {

    private static final String MARKER_FILE = "bounded_worlds_initialized.dat";

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

        // Phase 2: Biome guarantee (always runs — zones are in-memory only)
        // S2 fix: Always clear zones on startup to avoid stale data from previous session
        ForcedBiomeZoneManager.clear();
        BiomeScanner.ScanResult biomeScanResult = handleBiomePhase(overworld, radius);

        // Phase 3: Structure guarantee (only on first run — structures are permanent)
        if (firstRun) {
            handleStructurePhase(overworld, radius, biomeScanResult);

            // Write marker file
            try {
                Files.createDirectories(markerPath.getParent());
                Files.writeString(markerPath, "Bounded Worlds initialized. Delete this file to re-run border and structure setup.");
                BoundedWorlds.LOGGER.info("[Bounded Worlds] First-run setup complete. Marker file written.");
            } catch (IOException e) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not write marker file: {}", e.getMessage());
            }
        }
    }

    private BiomeScanner.ScanResult handleBiomePhase(ServerLevel overworld, int radius) {
        List<? extends String> biomeEntries = ModConfigs.REQUIRED_BIOMES.get();

        List<BiomeRequirement> requirements = new ArrayList<>();
        for (String entry : biomeEntries) {
            BiomeRequirement req = BiomeRequirement.parse(entry);
            if (req != null) {
                requirements.add(req);
            }
        }

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Scanning biomes within {} block radius...", radius);
        BiomeScanner.ScanResult result = BiomeScanner.scan(overworld, radius, requirements);

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Biome scan complete in {}ms. Sampled {} points, found {} unique biomes.",
                result.scanTimeMs(), result.sampledPoints(), result.foundBiomeIds().size());

        if (requirements.isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] No required biomes configured.");
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
            BoundedWorlds.LOGGER.info("[Bounded Worlds] All {} required biomes/tags are present!", requirements.size());
            return result;
        }

        BoundedWorlds.LOGGER.warn("[Bounded Worlds] {} of {} required biomes/tags are MISSING.",
                result.missing().size(), requirements.size());

        // Phase 2b: Force-place missing biomes
        BiomeZoneSize sizeCategory = ModConfigs.FORCED_BIOME_SIZE.get();

        List<ForcedBiomeZone> zones = BiomeZonePlanner.planZones(
                overworld, result.missing(), result, radius, sizeCategory);

        for (ForcedBiomeZone zone : zones) {
            ForcedBiomeZoneManager.addZone(zone);
        }

        if (!zones.isEmpty()) {
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Registered {} forced biome zone(s).", zones.size());

            // Inject forced biome zones into the scan result so StructureScanner can use them
            for (ForcedBiomeZone zone : zones) {
                BlockPos zonePos = new BlockPos(zone.centerX(), 64, zone.centerZ());
                result.biomeLocations().putIfAbsent(zone.biome(), zonePos);
            }
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

        List<String> structureIds = new ArrayList<>(structureEntries);
        StructureScanner.ScanResult result = StructureScanner.scanAndPlace(overworld, radius, structureIds, biomeScanResult);

        BoundedWorlds.LOGGER.info("[Bounded Worlds] Structure scan complete in {}ms. Found: {}, Placed: {}, Failed: {}",
                result.scanTimeMs(), result.found().size(), result.placed().size(), result.failed().size());

        if (!result.failed().isEmpty()) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Failed to place structures: {}", result.failed());
        }
    }
}
