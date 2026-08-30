package net.denlille.bounded_worlds.config;

import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ModConfigs {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue WORLD_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REQUIRED_BIOMES;
    public static final ForgeConfigSpec.EnumValue<BiomeZoneSize> FORCED_BIOME_SIZE;
    public static final ForgeConfigSpec.BooleanValue TERRAIN_SHAPING;
    public static final ForgeConfigSpec.BooleanValue BORDER_BIOME_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> BORDER_BIOME;
    public static final ForgeConfigSpec.IntValue BORDER_BIOME_WIDTH;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REQUIRED_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NETHER_REQUIRED_BIOMES;
    public static final ForgeConfigSpec.BooleanValue DIRECTIONAL_ENABLED;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HOT_DIRECTION;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HUMID_DIRECTION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Bounded Worlds Configuration");
        builder.push("world");

        WORLD_RADIUS = builder
                .comment("World radius in blocks around spawn; sets the world border on first world creation.",
                         "Delete 'bounded_worlds_initialized.dat' in the world folder to re-apply.")
                .defineInRange("worldRadius", 3000, 100, 20000);

        REQUIRED_BIOMES = builder
                .comment("Biomes or #tags that must exist within the border, e.g. \"minecraft:mushroom_fields\", \"#forge:is_hot\".",
                         "Missing ones are generated as natural-looking zones.")
                .defineListAllowEmpty("requiredBiomes",
                        List.of(),
                        obj -> obj instanceof String s && !s.isBlank());

        FORCED_BIOME_SIZE = builder
                .comment("Size of forced biome zones: SMALL 100-200, MEDIUM 250-350, LARGE 400-600 blocks.")
                .defineEnum("forcedBiomeSize", BiomeZoneSize.MEDIUM);

        TERRAIN_SHAPING = builder
                .comment("Reshape terrain when it doesn't fit a forced biome (raise islands, carve sea basins).",
                         "Disable if another mod conflicts with terrain generation.")
                .define("terrainShaping", true);

        BORDER_BIOME_ENABLED = builder
                .comment("Terraria-style ring of a fixed biome hugging the world border, terrain reshaped to fit.",
                         "Don't change these options after exploring the border area — edits create seams.")
                .define("borderBiomeEnabled", false);

        BORDER_BIOME = builder
                .comment("Biome of the border ring.")
                .define("borderBiome", "minecraft:ocean");

        BORDER_BIOME_WIDTH = builder
                .comment("Ring width in blocks, measured inward from the world border.")
                .defineInRange("borderBiomeWidth", 256, 64, 2048);

        REQUIRED_STRUCTURES = builder
                .comment("Structure IDs that must exist within the border, e.g. \"minecraft:monument\".",
                         "Missing ones are force-placed. Checked on first world creation only.")
                .defineListAllowEmpty("requiredStructures",
                        List.of(),
                        obj -> obj instanceof String s && !s.isBlank());

        builder.pop(); // world

        builder.comment("Nether requirements. Zones are placed within worldRadius / 8 of the scaled spawn",
                        "(the Nether shares the overworld's border).");
        builder.push("nether");

        NETHER_REQUIRED_BIOMES = builder
                .comment("Biomes or #tags required in the Nether, e.g. \"minecraft:crimson_forest\".",
                         "Nether biomes only; prefer a SMALL forcedBiomeSize when listing several.")
                .defineListAllowEmpty("requiredBiomes",
                        List.of(),
                        obj -> obj instanceof String s && !s.isBlank());

        builder.pop(); // nether

        builder.comment("Terraria-style directional layout: hot/cold and humid/dry biome axes around spawn.");
        builder.push("directional");

        DIRECTIONAL_ENABLED = builder
                .comment("Place forced biome zones in quadrants matching their temperature and humidity.")
                .define("enabled", false);

        HOT_DIRECTION = builder
                .comment("Direction for hot biomes (cold = opposite). Perpendicular to humidDirection. RANDOM = seed-based.")
                .defineEnum("hotDirection", CompassDirection.SOUTH);

        HUMID_DIRECTION = builder
                .comment("Direction for humid biomes (dry = opposite). Perpendicular to hotDirection. RANDOM = seed-based.")
                .defineEnum("humidDirection", CompassDirection.EAST);

        builder.pop(); // directional

        SPEC = builder.build();
    }
}
