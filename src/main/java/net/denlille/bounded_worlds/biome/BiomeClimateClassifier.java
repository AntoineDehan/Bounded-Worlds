package net.denlille.bounded_worlds.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

/**
 * Classifies biomes by temperature (Forge tags) and humidity (hardcoded sets).
 * Used for directional biome placement and zone planning.
 */
public class BiomeClimateClassifier {

    // Temperature: Forge tags
    private static final TagKey<Biome> IS_HOT = TagKey.create(Registries.BIOME,
            new ResourceLocation("forge", "is_hot/overworld"));
    private static final TagKey<Biome> IS_COLD = TagKey.create(Registries.BIOME,
            new ResourceLocation("forge", "is_cold/overworld"));

    // Humidity: hardcoded since no Forge tag exists
    private static final Set<ResourceLocation> HUMID_BIOMES = Set.of(
            new ResourceLocation("minecraft", "swamp"),
            new ResourceLocation("minecraft", "mangrove_swamp"),
            new ResourceLocation("minecraft", "jungle"),
            new ResourceLocation("minecraft", "bamboo_jungle"),
            new ResourceLocation("minecraft", "sparse_jungle"),
            new ResourceLocation("minecraft", "mushroom_fields"),
            new ResourceLocation("minecraft", "lush_caves"),
            new ResourceLocation("minecraft", "dark_forest"),
            new ResourceLocation("minecraft", "old_growth_birch_forest"),
            new ResourceLocation("minecraft", "old_growth_pine_taiga"),
            new ResourceLocation("minecraft", "old_growth_spruce_taiga"),
            new ResourceLocation("minecraft", "flower_forest")
    );

    private static final Set<ResourceLocation> DRY_BIOMES = Set.of(
            new ResourceLocation("minecraft", "desert"),
            new ResourceLocation("minecraft", "badlands"),
            new ResourceLocation("minecraft", "eroded_badlands"),
            new ResourceLocation("minecraft", "wooded_badlands"),
            new ResourceLocation("minecraft", "savanna"),
            new ResourceLocation("minecraft", "savanna_plateau"),
            new ResourceLocation("minecraft", "windswept_savanna"),
            new ResourceLocation("minecraft", "ice_spikes"),
            new ResourceLocation("minecraft", "snowy_plains"),
            new ResourceLocation("minecraft", "stony_peaks"),
            new ResourceLocation("minecraft", "stony_shore")
    );

    public enum TemperatureCategory {
        HOT, COLD, TEMPERATE
    }

    public enum HumidityCategory {
        HUMID, DRY, NEUTRAL
    }

    public static TemperatureCategory getTemperature(Holder<Biome> biome) {
        if (biome.is(IS_HOT)) return TemperatureCategory.HOT;
        if (biome.is(IS_COLD)) return TemperatureCategory.COLD;

        // Fallback for biomes without Forge tags (typically modded biomes):
        // classify by base temperature (desert/savanna = 2.0, snowy = <= 0.05)
        float temperature = biome.value().getBaseTemperature();
        if (temperature >= 1.0f) return TemperatureCategory.HOT;
        if (temperature <= 0.15f) return TemperatureCategory.COLD;
        return TemperatureCategory.TEMPERATE;
    }

    public static HumidityCategory getHumidity(Holder<Biome> biome) {
        ResourceLocation biomeId = biome.unwrapKey()
                .map(ResourceKey::location)
                .orElse(null);
        if (biomeId == null) return HumidityCategory.NEUTRAL;

        // Curated vanilla lists take precedence
        if (HUMID_BIOMES.contains(biomeId)) return HumidityCategory.HUMID;
        if (DRY_BIOMES.contains(biomeId)) return HumidityCategory.DRY;

        // Fallback for unlisted (typically modded) biomes: downfall heuristic.
        // The humid check is skipped for cold biomes — snowy biomes have high
        // downfall (frozen_peaks: 0.9) but snow is not a humid climate.
        float downfall = biome.value().getModifiedClimateSettings().downfall();
        float temperature = biome.value().getBaseTemperature();
        if (downfall >= 0.85f && temperature > 0.3f) return HumidityCategory.HUMID;
        if (downfall <= 0.15f) return HumidityCategory.DRY;
        return HumidityCategory.NEUTRAL;
    }
}
