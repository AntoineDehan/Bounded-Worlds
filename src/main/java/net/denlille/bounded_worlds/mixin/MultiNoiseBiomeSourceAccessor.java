package net.denlille.bounded_worlds.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private parameter list of MultiNoiseBiomeSource so the zone
 * planner can match forced biomes against the climate points vanilla uses.
 */
@Mixin(MultiNoiseBiomeSource.class)
public interface MultiNoiseBiomeSourceAccessor {

    @Invoker("parameters")
    Climate.ParameterList<Holder<Biome>> boundedWorlds$parameters();
}
