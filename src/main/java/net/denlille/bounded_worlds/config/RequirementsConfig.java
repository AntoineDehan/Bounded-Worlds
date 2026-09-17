package net.denlille.bounded_worlds.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads all biome/structure requirements from config/bounded_worlds-requirements.json —
 * one file for every dimension (vanilla and modded). JSON because ForgeConfigSpec
 * cannot express dynamic per-dimension tables or per-structure objects in TOML.
 *
 * {
 *   "config_version": 1,
 *   "dimensions": {
 *     "minecraft:overworld": {
 *       "requiredBiomes": ["minecraft:mushroom_fields", "#forge:is_hot"],
 *       "structures": {
 *         "minecraft:monument": { "min": 1, "max": 1 },
 *         "minecraft:village": 3
 *       }
 *     }
 *   }
 * }
 *
 * A structure entry is either a number (= min) or an object with "min"/"max".
 * Keys starting with "_" are ignored (comments). "config_version" identifies
 * the format so future versions can convert old files instead of breaking them.
 */
public final class RequirementsConfig {

    // Display name used in logs; the file lives in config/bounded_worlds/
    public static final String FILE_NAME = ConfigFolder.FOLDER_NAME + "/requirements.json";
    public static final int CURRENT_VERSION = 1;

    private static final int MIN_CAP = 100;

    /** One structure requirement: at least {@code min} instances; {@code max} caps natural generation (-1 = no cap). */
    public record StructureRequirement(String id, int min, int max) {
        public boolean hasMax() { return max >= 0; }
    }

    /** Requirements for one dimension. */
    public record DimensionRequirements(List<String> requiredBiomes, List<StructureRequirement> structures) {
        public static final DimensionRequirements EMPTY = new DimensionRequirements(List.of(), List.of());
    }

    private RequirementsConfig() {}

    /**
     * Returns requirements per dimension id, in file order (deterministic);
     * creates a commented default file (migrating pre-0.6.0 settings) if absent.
     * A present but unparseable file throws — failing fast beats initializing
     * a world without its guarantees.
     */
    public static Map<ResourceLocation, DimensionRequirements> load() {
        Path path = ConfigFolder.folder().resolve("requirements.json");
        if (!Files.exists(path)) {
            migrateIfNeeded(); // normally done at mod construction; covers deletion at runtime
            if (!Files.exists(path)) {
                // Creation aborted — the migration already logged why
                BoundedWorlds.LOGGER.error("[Bounded Worlds] {} could not be created — no requirements loaded.", FILE_NAME);
                return Map.of();
            }
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("[Bounded Worlds] config/" + FILE_NAME + " is invalid JSON (" +
                    e.getMessage() + "). Fix or delete the file, then restart — starting the world without it " +
                    "would permanently skip its biome/structure guarantees.", e);
        }

        int version = 1;
        if (root.has("config_version")) {
            try {
                version = root.get("config_version").getAsInt();
            } catch (Exception ignored) {}
        } else {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] {} has no config_version — assuming {}.", FILE_NAME, CURRENT_VERSION);
        }
        if (version > CURRENT_VERSION) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] {} is config_version {} but this mod version knows {} — " +
                    "reading anyway, some entries may be ignored.", FILE_NAME, version, CURRENT_VERSION);
        }
        // version < CURRENT_VERSION: converters go here when the format evolves.

        JsonElement dimensionsElement = root.get("dimensions");
        if (dimensionsElement == null || !dimensionsElement.isJsonObject()) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: missing \"dimensions\" object — no requirements loaded.", FILE_NAME);
            return Map.of();
        }

        Map<ResourceLocation, DimensionRequirements> byDimension = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : dimensionsElement.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) continue; // comment/example keys

            ResourceLocation dimensionId = ResourceLocation.tryParse(key);
            if (dimensionId == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: invalid dimension id \"{}\" — entry skipped.", FILE_NAME, key);
                continue;
            }

            DimensionRequirements requirements = readDimension(key, entry.getValue());
            if (requirements != null
                    && !(requirements.requiredBiomes().isEmpty() && requirements.structures().isEmpty())) {
                byDimension.put(dimensionId, requirements);
            }
        }
        return byDimension;
    }

    @Nullable
    private static DimensionRequirements readDimension(String key, JsonElement value) {
        // Bare array kept as shorthand for {"requiredBiomes": [...]}
        if (value.isJsonArray()) {
            List<String> biomes = readStringList(value.getAsJsonArray());
            return biomes == null ? invalidDimension(key) : new DimensionRequirements(biomes, List.of());
        }
        if (!value.isJsonObject()) {
            return invalidDimension(key);
        }
        JsonObject obj = value.getAsJsonObject();

        List<String> biomes = List.of();
        if (obj.has("requiredBiomes")) {
            if (!obj.get("requiredBiomes").isJsonArray()
                    || (biomes = readStringList(obj.getAsJsonArray("requiredBiomes"))) == null) {
                return invalidDimension(key);
            }
        }

        List<StructureRequirement> structures = new ArrayList<>();
        if (obj.has("structures")) {
            if (!obj.get("structures").isJsonObject()) {
                return invalidDimension(key);
            }
            for (Map.Entry<String, JsonElement> structureEntry : obj.getAsJsonObject("structures").entrySet()) {
                String structureId = structureEntry.getKey();
                if (structureId.startsWith("_")) continue;
                StructureRequirement requirement = readStructure(key, structureId, structureEntry.getValue());
                if (requirement != null) {
                    structures.add(requirement);
                }
            }
        }
        return new DimensionRequirements(biomes, List.copyOf(structures));
    }

    @Nullable
    private static StructureRequirement readStructure(String dimensionKey, String structureId, JsonElement value) {
        int min = 1;
        int max = -1;
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                min = value.getAsInt();
            } else if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                if (obj.has("min")) min = obj.get("min").getAsInt();
                if (obj.has("max")) max = obj.get("max").getAsInt();
            } else {
                throw new IllegalArgumentException("must be a number (= min) or an object with min/max");
            }
            if (min < 0 || min > MIN_CAP) {
                throw new IllegalArgumentException("min must be between 0 and " + MIN_CAP);
            }
            if (max >= 0 && max < min) {
                throw new IllegalArgumentException("max must be >= min");
            }
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: structure \"{}\" in \"{}\" is invalid ({}) — entry skipped.",
                    FILE_NAME, structureId, dimensionKey, e.getMessage());
            return null;
        }
        return new StructureRequirement(structureId, min, max);
    }

    @Nullable
    private static List<String> readStringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                String value = element.getAsString();
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return values;
    }

    @Nullable
    private static DimensionRequirements invalidDimension(String key) {
        BoundedWorlds.LOGGER.warn("[Bounded Worlds] {}: entry \"{}\" must be an object with " +
                "\"requiredBiomes\" and/or \"structures\" — entry skipped.", FILE_NAME, key);
        return null;
    }

    // ------------------------------------------------------------------
    // Migration — called once from the mod constructor, BEFORE Forge loads
    // (and corrects) the TOML: the pre-0.6.0 keys are still readable there.
    // ------------------------------------------------------------------

    /** Creates the requirements file if absent, seeded from pre-0.6.0 config values. */
    public static void migrateIfNeeded() {
        Path path = ConfigFolder.folder().resolve("requirements.json");
        if (Files.exists(path)) {
            return;
        }

        JsonObject dimensions = new JsonObject();
        Boolean tomlResult = migrateFromToml(dimensions);
        if (tomlResult == null) {
            // Creating the file now would lose the unreadable TOML's old values — retry next launch
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Skipping creation of {} this launch so pre-0.6.0 " +
                    "values are not lost — fix {}/common.toml and restart.", FILE_NAME, ConfigFolder.FOLDER_NAME);
            return;
        }
        boolean migrated = tomlResult;
        migrated |= migrateFromDimensionsJson(dimensions);

        JsonObject root = new JsonObject();
        root.addProperty("config_version", CURRENT_VERSION);
        JsonArray comment = new JsonArray();
        comment.add("Biome and structure requirements per dimension (vanilla and modded), guaranteed within the border.");
        comment.add("requiredBiomes: biome ids or #tags, generated as natural-looking zones when missing.");
        comment.add("structures: id -> number (= min instances) or { \"min\": n, \"max\": n }.");
        comment.add("min instances are force-placed if missing (overworld only, checked on first world creation).");
        comment.add("max (0 = disabled, 1 = unique) is accepted but NOT enforced yet - planned for a future version.");
        comment.add("Keys starting with an underscore are ignored. Do not edit config_version.");
        comment.add("Example: \"minecraft:overworld\": { \"requiredBiomes\": [\"minecraft:mushroom_fields\"], " +
                "\"structures\": { \"minecraft:monument\": { \"min\": 1 } } }");
        root.add("_comment", comment);
        root.add("dimensions", dimensions);

        try {
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Created {}{}.", FILE_NAME,
                    migrated ? " (migrated pre-0.6.0 requirements from the old config keys)" : "");
        } catch (Exception e) {
            BoundedWorlds.LOGGER.error("[Bounded Worlds] Could not create {}: {}", FILE_NAME, e.getMessage());
        }
    }

    /**
     * Pulls the pre-0.6.0 requirement keys out of the TOML (already moved into
     * the config folder by ConfigFolder.setup()). True: migrated; false:
     * nothing to migrate; null: TOML unreadable — do not create the file yet.
     */
    @Nullable
    private static Boolean migrateFromToml(JsonObject dimensions) {
        Path tomlPath = ConfigFolder.folder().resolve("common.toml");
        if (!Files.exists(tomlPath)) {
            return false;
        }

        List<String> overworldBiomes;
        List<String> overworldStructures;
        List<String> netherBiomes;
        try (FileConfig toml = FileConfig.of(tomlPath)) {
            toml.load();
            overworldBiomes = stringList(toml.get("world.requiredBiomes"));
            overworldStructures = stringList(toml.get("world.requiredStructures"));
            netherBiomes = stringList(toml.get("nether.requiredBiomes"));
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not read old TOML values for migration: {}", e.getMessage());
            return null;
        }

        boolean migrated = false;
        if (!overworldBiomes.isEmpty() || !overworldStructures.isEmpty()) {
            JsonObject overworld = new JsonObject();
            if (!overworldBiomes.isEmpty()) {
                overworld.add("requiredBiomes", toJsonArray(overworldBiomes));
            }
            if (!overworldStructures.isEmpty()) {
                JsonObject structures = new JsonObject();
                for (String id : overworldStructures) {
                    JsonObject requirement = new JsonObject();
                    requirement.addProperty("min", 1);
                    structures.add(id, requirement);
                }
                overworld.add("structures", structures);
            }
            dimensions.add("minecraft:overworld", overworld);
            migrated = true;
        }
        if (!netherBiomes.isEmpty()) {
            JsonObject netherObj = new JsonObject();
            netherObj.add("requiredBiomes", toJsonArray(netherBiomes));
            dimensions.add("minecraft:the_nether", netherObj);
            migrated = true;
        }
        return migrated;
    }

    /** Absorbs the (never-released) bounded_worlds-dimensions.json, then renames it. */
    private static boolean migrateFromDimensionsJson(JsonObject dimensions) {
        Path oldPath = FMLPaths.CONFIGDIR.get().resolve("bounded_worlds-dimensions.json");
        if (!Files.exists(oldPath)) {
            return false;
        }

        boolean migrated = false;
        try {
            JsonObject old = JsonParser.parseString(Files.readString(oldPath)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : old.entrySet()) {
                if (entry.getKey().startsWith("_") || dimensions.has(entry.getKey())) continue;
                JsonElement value = entry.getValue();
                JsonObject dimension = new JsonObject();
                if (value.isJsonArray()) {
                    dimension.add("requiredBiomes", value);
                } else if (value.isJsonObject() && value.getAsJsonObject().has("requiredBiomes")) {
                    dimension.add("requiredBiomes", value.getAsJsonObject().get("requiredBiomes"));
                } else {
                    continue;
                }
                dimensions.add(entry.getKey(), dimension);
                migrated = true;
            }
            Files.move(oldPath, oldPath.resolveSibling(oldPath.getFileName() + ".migrated"));
            BoundedWorlds.LOGGER.info("[Bounded Worlds] bounded_worlds-dimensions.json merged into {} and renamed.", FILE_NAME);
        } catch (Exception e) {
            BoundedWorlds.LOGGER.warn("[Bounded Worlds] Could not migrate bounded_worlds-dimensions.json: {}", e.getMessage());
        }
        return migrated;
    }

    private static List<String> stringList(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String s && !s.isBlank()) {
                strings.add(s.trim());
            }
        }
        return strings;
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
