package net.denlille.bounded_worlds.config;

import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfigs {

    // Format version of this TOML — bump when keys move or change meaning, so
    // future versions can read old values (raw, before Forge's correction
    // strips them) and migrate instead of silently dropping them.
    public static final int TOML_VERSION = 2;

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue CONFIG_VERSION;
    public static final ForgeConfigSpec.IntValue SMALL_RADIUS;
    public static final ForgeConfigSpec.IntValue MEDIUM_RADIUS;
    public static final ForgeConfigSpec.IntValue LARGE_RADIUS;
    public static final ForgeConfigSpec.EnumValue<WorldSize> DEFAULT_WORLD_SIZE;
    public static final ForgeConfigSpec.EnumValue<BiomeZoneSize> FORCED_BIOME_SIZE;
    public static final ForgeConfigSpec.BooleanValue TERRAIN_SHAPING;
    public static final ForgeConfigSpec.BooleanValue BORDER_BIOME_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> BORDER_BIOME;
    public static final ForgeConfigSpec.IntValue BORDER_BIOME_WIDTH;
    public static final ForgeConfigSpec.BooleanValue DIRECTIONAL_ENABLED;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HOT_DIRECTION;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HUMID_DIRECTION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Bounded Worlds Configuration",
                        "Biome and structure requirements (all dimensions) live in bounded_worlds-requirements.json next to this file.");

        CONFIG_VERSION = builder
                .comment("Internal config format version — do not edit.")
                .defineInRange("configVersion", TOML_VERSION, 1, Integer.MAX_VALUE);

        builder.push("world");

        SMALL_RADIUS = builder
                .comment("Radius in blocks of each world size tier, selectable in the world-creation screen.",
                         "The border is set on first world creation, centered on spawn.")
                .defineInRange("smallRadius", 1500, 100, 20000);

        MEDIUM_RADIUS = builder
                .defineInRange("mediumRadius", 3000, 100, 20000);

        LARGE_RADIUS = builder
                .defineInRange("largeRadius", 5000, 100, 20000);

        DEFAULT_WORLD_SIZE = builder
                .comment("Size used when none was picked in the creation screen (dedicated servers,",
                         "or re-init after deleting 'bounded_worlds_initialized.dat' in the world folder).")
                .defineEnum("defaultWorldSize", WorldSize.MEDIUM);

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

        builder.pop(); // world

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
