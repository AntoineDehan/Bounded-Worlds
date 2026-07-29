package net.denlille.bounded_worlds.mixin;

import net.denlille.bounded_worlds.biome.DirectionalClimateManager;
import net.denlille.bounded_worlds.biome.ForcedBiomeZoneManager;
import net.denlille.bounded_worlds.biome.ZoneClimateTarget;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adjusts the vanilla climate sampler in two stages:
 * 1. Directional bias — gradually hotter/more humid toward configured directions.
 * 2. Forced-zone morphing — inside a forced zone the climate is pulled fully to
 *    the target biome's parameter point (vanilla then selects that biome itself),
 *    fading across a halo so vanilla generates legitimate intermediate biomes
 *    at the edges instead of a hard painted border.
 *
 * Depth is never touched: it is Y-dependent (caves below forced zones stay caves).
 *
 * Priority 500 to run before TerraBlender (default 1000), consistent with BiomeSourceMixin.
 */
@Mixin(value = Climate.Sampler.class, priority = 500)
public class ClimateSamplerMixin {

    @Inject(method = "sample", at = @At("RETURN"), cancellable = true)
    private void boundedWorlds$adjustClimate(int pX, int pY, int pZ,
                                              CallbackInfoReturnable<Climate.TargetPoint> cir) {
        // Identity lookup resolves the dimension; samplers of unregistered
        // dimensions (the End reads erosion from its sampler!) are never touched
        ForcedBiomeZoneManager.DimensionEntry entry = ForcedBiomeZoneManager.entryForSampler(this);
        if (entry == null) {
            return;
        }

        // Convert biome coordinates to block coordinates
        int blockX = pX << 2;
        int blockZ = pZ << 2;

        Climate.TargetPoint point = cir.getReturnValue();
        boolean changed = false;

        // Stage 1: directional climate bias (overworld only)
        if (entry.isOverworld() && DirectionalClimateManager.isEnabled()) {
            float tempBias = DirectionalClimateManager.getTemperatureBias(blockX, blockZ);
            float humidBias = DirectionalClimateManager.getHumidityBias(blockX, blockZ);

            // Skip if biases are negligible (near spawn center)
            if (Math.abs(tempBias) >= 0.001f || Math.abs(humidBias) >= 0.001f) {
                long newTemp = Climate.quantizeCoord(Climate.unquantizeCoord(point.temperature()) + tempBias);
                long newHumid = Climate.quantizeCoord(Climate.unquantizeCoord(point.humidity()) + humidBias);
                point = new Climate.TargetPoint(
                        newTemp,
                        newHumid,
                        point.continentalness(),
                        point.erosion(),
                        point.depth(),
                        point.weirdness()
                );
                changed = true;
            }
        }

        // Stage 2: forced-zone climate morphing
        ForcedBiomeZoneManager.ClimateMorph morph = ForcedBiomeZoneManager.getMorphAt(entry, blockX, blockZ);
        if (morph != null) {
            ZoneClimateTarget target = morph.target();
            double factor = morph.factor();
            point = new Climate.TargetPoint(
                    lerp(point.temperature(), target.temperature(), factor),
                    lerp(point.humidity(), target.humidity(), factor),
                    lerp(point.continentalness(), target.continentalness(), factor),
                    lerp(point.erosion(), target.erosion(), factor),
                    point.depth(),
                    lerp(point.weirdness(), target.weirdness(), factor)
            );
            changed = true;
        }

        if (changed) {
            cir.setReturnValue(point);
        }
    }

    private static long lerp(long from, long to, double factor) {
        return from + (long) ((to - from) * factor);
    }
}
