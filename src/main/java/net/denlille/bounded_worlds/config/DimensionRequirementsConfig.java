package net.denlille.bounded_worlds.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads per-dimension biome requirements from config/bounded_worlds-dimensions.json.
 * A JSON file is used because ForgeConfigSpec cannot express dynamic per-dimension
 * tables in TOML. Format (keys starting with "_" are ignored):
 *
 * {
 *   "twilightforest:twilight_forest": ["twilightforest:enchanted_forest", "#some:tag"],
 *   "undergarden:undergarden": { "requiredBiomes": ["undergarden:gronglegrowth"] }
 * }
 *
 * Both value forms are accepted — the object form leaves room for future
 * per-dimension options (radius, zone size...).
 */
public final class DimensionRequirementsConfig {

    public static final String FILE_NAME = "bounded_worlds-dimensions.json";

    private DimensionRequirementsConfig() {}

    /**
     * Returns required biomes per dimension id, in file order (deterministic).
     * Creates a commented example file on first run and returns an empty map.
     */
    public static Map<ResourceLocation, List<String>> load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);

        if (!Files.exists(path)) {
            writeDefault(path);
            return Map.of();
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not read {} ({}) — no custom dimension requirements loaded.",
                    FILE_NAME, e.getMessage());
            return Map.of();
        }

        Map<ResourceLocation, List<String>> byDimension = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) continue; // comment/example keys

            ResourceLocation dimensionId = ResourceLocation.tryParse(key);
            if (dimensionId == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: invalid dimension id \"{}\" — entry skipped.", FILE_NAME, key);
                continue;
            }

            List<String> biomes = readBiomeList(entry.getValue());
            if (biomes == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: entry \"{}\" must be a list of biomes " +
                        "or an object with a \"requiredBiomes\" list — entry skipped.", FILE_NAME, key);
                continue;
            }
            if (!biomes.isEmpty()) {
                byDimension.put(dimensionId, biomes);
            }
        }
        return byDimension;
    }

    private static List<String> readBiomeList(JsonElement value) {
        JsonArray array;
        if (value.isJsonArray()) {
            array = value.getAsJsonArray();
        } else if (value.isJsonObject() && value.getAsJsonObject().has("requiredBiomes")
                && value.getAsJsonObject().get("requiredBiomes").isJsonArray()) {
            array = value.getAsJsonObject().getAsJsonArray("requiredBiomes");
        } else {
            return null;
        }

        List<String> biomes = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                String biome = element.getAsString();
                if (!biome.isBlank()) {
                    biomes.add(biome.trim());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return biomes;
    }

    private static void writeDefault(Path path) {
        String content = """
                {
                  "_comment": [
                    "Required biomes per modded/datapack dimension.",
                    "(The overworld and the Nether have their own sections in bounded_worlds-common.toml.)",
                    "Key = dimension id, value = list of biome ids or #tags.",
                    "Keys starting with an underscore are ignored.",
                    "Only dimensions using vanilla multi-noise biome generation are supported;",
                    "others are skipped with a warning in the log.",
                    "Zones are placed within worldRadius of the world spawn coordinates.",
                    "Example:",
                    "  \\"twilightforest:twilight_forest\\": [\\"twilightforest:enchanted_forest\\"]"
                  ]
                }
                """;
        try {
            Files.writeString(path, content);
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Created default {}.", FILE_NAME);
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not create {}: {}", FILE_NAME, e.getMessage());
        }
    }
}
