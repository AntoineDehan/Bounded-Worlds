package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.Set;

public class BiomeRequirement {

    private final String raw;
    private final boolean isTag;
    @Nullable private final TagKey<Biome> tagKey;
    @Nullable private final ResourceLocation biomeId;

    private BiomeRequirement(String raw, boolean isTag, @Nullable TagKey<Biome> tagKey, @Nullable ResourceLocation biomeId) {
        this.raw = raw;
        this.isTag = isTag;
        this.tagKey = tagKey;
        this.biomeId = biomeId;
    }

    @Nullable
    public static BiomeRequirement parse(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }

        entry = entry.trim();

        if (entry.startsWith("#")) {
            String tagStr = entry.substring(1);
            ResourceLocation tagLocation = ResourceLocation.tryParse(tagStr);
            if (tagLocation == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Invalid biome tag: {}", entry);
                return null;
            }
            TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagLocation);
            return new BiomeRequirement(entry, true, tagKey, null);
        } else {
            ResourceLocation biomeId = ResourceLocation.tryParse(entry);
            if (biomeId == null) {
                BoundedWorlds.LOGGER.warn("[Bounded Worlds] Invalid biome ID: {}", entry);
                return null;
            }
            return new BiomeRequirement(entry, false, null, biomeId);
        }
    }

    public boolean test(Holder<Biome> biomeHolder) {
        if (isTag) {
            return biomeHolder.is(tagKey);
        } else {
            return biomeHolder.unwrapKey()
                    .map(ResourceKey::location)
                    .map(loc -> loc.equals(biomeId))
                    .orElse(false);
        }
    }

    public boolean isSatisfiedByAny(Set<Holder<Biome>> biomes) {
        for (Holder<Biome> biome : biomes) {
            if (test(biome)) {
                return true;
            }
        }
        return false;
    }

    public String description() {
        return raw;
    }

    public boolean isTag() {
        return isTag;
    }

    @Nullable
    public TagKey<Biome> tagKey() {
        return tagKey;
    }

    @Nullable
    public ResourceLocation biomeId() {
        return biomeId;
    }
}
