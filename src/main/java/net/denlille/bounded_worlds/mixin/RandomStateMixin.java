package net.denlille.bounded_worlds.mixin;

import net.denlille.bounded_worlds.biome.ZoneTerrainShaper;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the terrain density functions (finalDensity and
 * initialDensityWithoutJaggedness) with ZoneTerrainShaper so forced zones can
 * raise islands / carve ocean basins. The chunk generator reads both slots
 * from the router record, so replacing them here reshapes actual terrain —
 * unlike the climate sampler, which only affects biome selection.
 *
 * Wrapping happens for every dimension's RandomState (the constructor has no
 * dimension context), but the shaper itself is identity-gated to the
 * overworld's RandomState at compute time and is a pure passthrough
 * everywhere else.
 */
@Mixin(value = RandomState.class, priority = 500)
public abstract class RandomStateMixin {

    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void boundedWorlds$wrapTerrainDensity(NoiseGeneratorSettings settings,
                                                   HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
                                                   long seed, CallbackInfo ci) {
        DensityFunction shapedInitial = new ZoneTerrainShaper(this.router.initialDensityWithoutJaggedness(), this);
        DensityFunction shapedFinal = new ZoneTerrainShaper(this.router.finalDensity(), this);

        this.router = new NoiseRouter(
                this.router.barrierNoise(),
                this.router.fluidLevelFloodednessNoise(),
                this.router.fluidLevelSpreadNoise(),
                this.router.lavaNoise(),
                this.router.temperature(),
                this.router.vegetation(),
                this.router.continents(),
                this.router.erosion(),
                this.router.depth(),
                this.router.ridges(),
                shapedInitial,
                shapedFinal,
                this.router.veinToggle(),
                this.router.veinRidged(),
                this.router.veinGap());
    }
}
