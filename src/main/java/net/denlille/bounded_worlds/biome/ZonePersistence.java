package net.denlille.bounded_worlds.biome;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves forced biome zones to a JSON file in the world folder and loads them
 * back on startup. Zones used to be recomputed from scratch every launch,
 * which broke in three ways: structure-fallback zones (created on first run
 * only) vanished after a restart, editing requiredBiomes moved existing zones,
 * and mod updates changing the placement algorithm relocated zones on old
 * worlds. Persisted zones make placement stable for the lifetime of a world.
 */
public final class ZonePersistence {

    public static final String ZONES_FILE = "bounded_worlds_zones.json";
    private static final int FORMAT_VERSION = 1;

    private ZonePersistence() {}

    public static void save(Path path, List<ForcedBiomeZone> zones) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);

        JsonArray zoneArray = new JsonArray();
        for (ForcedBiomeZone zone : zones) {
            String biomeId = zone.biome().unwrapKey()
                    .map(ResourceKey::location)
                    .map(ResourceLocation::toString)
                    .orElse(null);
            if (biomeId == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Cannot persist zone with unregistered biome: {}", zone);
                continue;
            }

            JsonObject z = new JsonObject();
            z.addProperty("centerX", zone.centerX());
            z.addProperty("centerZ", zone.centerZ());
            z.addProperty("size", zone.size());
            z.addProperty("biome", biomeId);
            z.addProperty("description", zone.description());

            if (zone.terrainShaping() != ForcedBiomeZone.TerrainShaping.NONE) {
                z.addProperty("terrainShaping", zone.terrainShaping().name());
            }

            ZoneClimateTarget target = zone.climateTarget();
            if (target != null) {
                JsonObject t = new JsonObject();
                t.addProperty("temperature", target.temperature());
                t.addProperty("humidity", target.humidity());
                t.addProperty("continentalness", target.continentalness());
                t.addProperty("erosion", target.erosion());
                t.addProperty("weirdness", target.weirdness());
                z.add("climateTarget", t);
            }
            zoneArray.add(z);
        }
        root.add("zones", zoneArray);

        try {
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Persisted {} forced biome zone(s) to {}", zoneArray.size(), ZONES_FILE);
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not persist forced biome zones: {}", e.getMessage());
        }
    }

    /**
     * Loads persisted zones. Zones whose biome no longer exists (e.g. a mod
     * was removed) are dropped with a warning — the planner will then place a
     * fresh zone for the requirement, which may not line up with chunks that
     * were already generated.
     */
    public static List<ForcedBiomeZone> load(Path path, Registry<Biome> biomeRegistry) {
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            List<ForcedBiomeZone> zones = new ArrayList<>();

            for (JsonElement element : root.getAsJsonArray("zones")) {
                JsonObject z = element.getAsJsonObject();

                ResourceLocation biomeId = ResourceLocation.tryParse(z.get("biome").getAsString());
                Holder<Biome> biome = biomeId == null ? null
                        : biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, biomeId)).orElse(null);
                if (biome == null) {
                    BoundedWorlds.LOGGER.warn("[Bounded Worlds] Dropping persisted zone with unknown biome: {} — " +
                            "already-generated chunks may not match the replacement zone.", z.get("biome").getAsString());
                    continue;
                }

                ForcedBiomeZone.TerrainShaping terrainShaping = ForcedBiomeZone.TerrainShaping.NONE;
                if (z.has("terrainShaping")) {
                    try {
                        terrainShaping = ForcedBiomeZone.TerrainShaping.valueOf(z.get("terrainShaping").getAsString());
                    } catch (IllegalArgumentException ignored) {}
                }

                ZoneClimateTarget target = null;
                if (z.has("climateTarget")) {
                    JsonObject t = z.getAsJsonObject("climateTarget");
                    target = new ZoneClimateTarget(
                            t.get("temperature").getAsLong(),
                            t.get("humidity").getAsLong(),
                            t.get("continentalness").getAsLong(),
                            t.get("erosion").getAsLong(),
                            t.get("weirdness").getAsLong());
                }

                zones.add(new ForcedBiomeZone(
                        z.get("centerX").getAsInt(),
                        z.get("centerZ").getAsInt(),
                        z.get("size").getAsInt(),
                        biome,
                        z.get("description").getAsString(),
                        target,
                        terrainShaping));
            }

            BoundedWorlds.LOGGER.info("[Bounded Worlds] Loaded {} persisted forced biome zone(s).", zones.size());
            return zones;
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not read {} ({}) — zones will be re-planned.",
                    ZONES_FILE, e.getMessage());
            return List.of();
        }
    }
}
