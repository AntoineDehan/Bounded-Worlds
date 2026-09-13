package net.denlille.bounded_worlds.mixin.client;

import net.denlille.bounded_worlds.config.WorldSizeSelection;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    // "Create New World" clicked: the pending world size becomes the one the
    // upcoming server start will consume. require=0: degrade to the
    // defaultWorldSize config instead of crashing if a mod conflicts here.
    @Inject(method = "onCreate", at = @At("HEAD"), require = 0)
    private void boundedWorlds$armWorldSize(CallbackInfo ci) {
        WorldSizeSelection.armPending();
    }
}
