package net.denlille.bounded_worlds.mixin.client;

import net.denlille.bounded_worlds.client.WorldSizeUi;
import net.denlille.bounded_worlds.config.WorldSizeSelection;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    // Game(0) / World(1) / More(2)
    private static final int WORLD_TAB_INDEX = 1;

    // "Create New World" clicked. No world size picked yet: block the creation,
    // jump to the World tab and highlight the row (only when our row actually
    // injected — if a mod conflict skipped it, creation must keep working and
    // the defaultWorldSize config applies). Otherwise arm the picked size for
    // the upcoming first server start. require=0: degrade instead of crashing.
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true, require = 0)
    private void boundedWorlds$armWorldSize(CallbackInfo ci) {
        if (WorldSizeUi.isRowPresent() && WorldSizeSelection.pending() == null) {
            WorldSizeUi.flagMissingSelection();
            boundedWorlds$showWorldTab();
            ci.cancel();
            return;
        }
        WorldSizeSelection.armPending();
    }

    // The tab bar is found among the screen's children instead of shadowing the
    // private field: the MixinGradle AP fails to emit the field's refmap entry,
    // which would break the shadow in the obfuscated production runtime.
    @Unique
    private void boundedWorlds$showWorldTab() {
        for (GuiEventListener child : ((Screen) (Object) this).children()) {
            if (child instanceof TabNavigationBar bar) {
                bar.selectTab(WORLD_TAB_INDEX, true);
                return;
            }
        }
    }
}
