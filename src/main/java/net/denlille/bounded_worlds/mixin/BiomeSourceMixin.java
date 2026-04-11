package net.denlille.bounded_worlds.mixin;

import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiNoiseBiomeSource.class, priority = 500)
public class BiomeSourceMixin {

    // Priority 500 ensures we run BEFORE TerraBlender's mixin (default priority 1000).
    // When we set a return value, TerraBlender's injection is skipped for forced zones.
    @Inject(method = "getNoiseBiome", at = @At("HEAD"), cancellable = true)
    private void boundedWorlds$onGetNoiseBiome(int x, int y, int z, Climate.Sampler sampler,
                                                CallbackInfoReturnable<Holder<Biome>> cir) {
        int blockX = x << 2;
        int blockZ = z << 2;

        Holder<Biome> forced = ForcedBiomeZoneManager.getBiomeAt(blockX, blockZ);
        if (forced != null) {
            cir.setReturnValue(forced);
        }
    }
}
