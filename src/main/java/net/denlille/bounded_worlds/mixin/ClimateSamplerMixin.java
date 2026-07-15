package net.denlille.bounded_worlds.mixin;

import net.denlille.bounded_worlds.biome.DirectionalClimateManager;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects directional climate biases into the vanilla noise sampler.
 * This makes terrain shape (mountains, plains, etc.) gradually match
 * the expected biome direction — e.g., hotter terrain in the "hot" direction.
 *
 * Priority 500 to run before TerraBlender (default 1000), consistent with BiomeSourceMixin.
 */
@Mixin(value = Climate.Sampler.class, priority = 500)
public class ClimateSamplerMixin {

    @Inject(method = "sample", at = @At("RETURN"), cancellable = true)
    private void boundedWorlds$biasClimate(int pX, int pY, int pZ,
                                            CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (!DirectionalClimateManager.isEnabled()) return;

        // Convert biome coordinates to block coordinates
        int blockX = pX << 2;
        int blockZ = pZ << 2;

        float tempBias = DirectionalClimateManager.getTemperatureBias(blockX, blockZ);
        float humidBias = DirectionalClimateManager.getHumidityBias(blockX, blockZ);

        // Skip if biases are negligible (near spawn center)
        if (Math.abs(tempBias) < 0.001f && Math.abs(humidBias) < 0.001f) return;

        Climate.TargetPoint original = cir.getReturnValue();

        // Convert long values to floats, add bias, convert back
        float origTemp = Climate.unquantizeCoord(original.temperature());
        float origHumid = Climate.unquantizeCoord(original.humidity());

        long newTemp = Climate.quantizeCoord(origTemp + tempBias);
        long newHumid = Climate.quantizeCoord(origHumid + humidBias);

        cir.setReturnValue(new Climate.TargetPoint(
                newTemp,
                newHumid,
                original.continentalness(),
                original.erosion(),
                original.depth(),
                original.weirdness()
        ));
    }
}
