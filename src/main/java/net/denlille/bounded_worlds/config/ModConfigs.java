package net.denlille.bounded_worlds.config;

import net.denlille.bounded_worlds.biome.BiomeZoneSize;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ModConfigs {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue WORLD_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REQUIRED_BIOMES;
    public static final ForgeConfigSpec.EnumValue<BiomeZoneSize> FORCED_BIOME_SIZE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REQUIRED_STRUCTURES;
    public static final ForgeConfigSpec.BooleanValue DIRECTIONAL_ENABLED;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HOT_DIRECTION;
    public static final ForgeConfigSpec.EnumValue<CompassDirection> HUMID_DIRECTION;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Bounded Worlds Configuration");
        builder.push("world");

        WORLD_RADIUS = builder
                .comment("World radius in blocks from spawn point.",
                         "The world border will be set to this radius around the spawn.",
                         "Only applied on first world creation. Delete 'bounded_worlds_initialized.dat' in the world folder to re-apply.")
                .defineInRange("worldRadius", 3000, 100, 20000);

        REQUIRED_BIOMES = builder
                .comment("List of biomes or biome tags required within the world border.",
                         "Use biome IDs: \"minecraft:mushroom_fields\"",
                         "Use tags with # prefix: \"#forge:is_jungle\"",
                         "On server start, the mod scans and reports which are present or missing.")
                .defineListAllowEmpty("requiredBiomes",
                        List.of(),
                        obj -> obj instanceof String s && !s.isBlank());

        FORCED_BIOME_SIZE = builder
                .comment("Size category of forced biome zones.",
                         "When a required biome is missing, a zone with a random size in this range is created.",
                         "SMALL = 100-200 blocks, MEDIUM = 250-350 blocks (recommended), LARGE = 400-600 blocks.",
                         "Each zone gets a different random size within the chosen range.")
                .defineEnum("forcedBiomeSize", BiomeZoneSize.MEDIUM);

        REQUIRED_STRUCTURES = builder
                .comment("List of structure IDs required within the world border.",
                         "Example: \"minecraft:stronghold\", \"minecraft:monument\"",
                         "If a structure is not found within the radius, it will be force-placed in a valid biome.",
                         "Only checked on first world creation. Delete 'bounded_worlds_initialized.dat' to re-run.")
                .defineListAllowEmpty("requiredStructures",
                        List.of(),
                        obj -> obj instanceof String s && !s.isBlank());

        builder.pop(); // world

        builder.comment("Terraria-style directional biome placement.",
                         "When enabled, biomes are placed based on temperature and humidity axes.",
                         "Hot biomes go in one direction, cold in the opposite.",
                         "Humid biomes go in a perpendicular direction, dry in the opposite.");
        builder.push("directional");

        DIRECTIONAL_ENABLED = builder
                .comment("Enable directional biome placement.",
                         "When enabled, forced biome zones are placed in the correct quadrant",
                         "based on their temperature (hot/cold) and humidity (humid/dry).")
                .define("enabled", false);

        HOT_DIRECTION = builder
                .comment("Compass direction for hot biomes. Cold biomes go in the opposite direction.",
                         "NORTH, SOUTH, EAST, WEST, or RANDOM (chosen from world seed).",
                         "Must be perpendicular to humidDirection (unless RANDOM).")
                .defineEnum("hotDirection", CompassDirection.SOUTH);

        HUMID_DIRECTION = builder
                .comment("Compass direction for humid biomes. Dry biomes go in the opposite direction.",
                         "NORTH, SOUTH, EAST, WEST, or RANDOM (chosen from world seed).",
                         "Must be perpendicular to hotDirection (unless RANDOM).")
                .defineEnum("humidDirection", CompassDirection.EAST);

        builder.pop(); // directional

        SPEC = builder.build();
    }
}
