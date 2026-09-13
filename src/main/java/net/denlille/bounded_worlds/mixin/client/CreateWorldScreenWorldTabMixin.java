package net.denlille.bounded_worlds.mixin.client;

import net.denlille.bounded_worlds.client.WorldSizeUi;
import net.denlille.bounded_worlds.config.WorldSize;
import net.denlille.bounded_worlds.config.WorldSizeSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.EnumMap;
import java.util.Map;

/**
 * Adds a "World Size" row (Small / Medium / Large) to the World tab of the
 * world-creation screen. Nothing is preselected — the player must pick one
 * (CreateWorldScreenMixin blocks creation otherwise); the picked button is
 * shown pressed (inactive). The choice is armed when the player clicks
 * "Create New World" and consumed by WorldBorderHandler on first server start.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {

    // Captures the tab's 2-column RowHelper local so our row follows the
    // vanilla layout (same slot pattern as the seed section: full-width child).
    // require=0 + FAILSOFT: if another mod reshapes this constructor the row is
    // skipped with a Mixin warning instead of crashing — the mod still works,
    // falling back to the defaultWorldSize config.
    @Inject(method = "<init>", at = @At("TAIL"), require = 0, locals = LocalCapture.CAPTURE_FAILSOFT)
    private void boundedWorlds$addWorldSizeRow(CreateWorldScreen screen, CallbackInfo ci,
                                               GridLayout.RowHelper rowHelper) {
        WorldSizeSelection.resetPending();

        GridLayout section = new GridLayout().rowSpacing(4);
        GridLayout.RowHelper rows = section.createRowHelper(1);
        StringWidget title = new StringWidget(
                Component.translatable("bounded_worlds.createWorld.worldSize"),
                Minecraft.getInstance().font).alignLeft();
        WorldSizeUi.setTitle(title);
        rows.addChild(title);

        GridLayout buttonRow = new GridLayout().columnSpacing(5);
        GridLayout.RowHelper columns = buttonRow.createRowHelper(WorldSize.values().length);
        Map<WorldSize, Button> buttons = new EnumMap<>(WorldSize.class);
        for (WorldSize size : WorldSize.values()) {
            Button button = Button.builder(Component.translatable(size.translationKey()),
                            b -> boundedWorlds$select(size, buttons))
                    .width(100)
                    .build();
            button.setTooltip(Tooltip.create(Component.translatable(
                    "bounded_worlds.createWorld.worldSize.radius", size.radius())));
            buttons.put(size, button);
            columns.addChild(button);
        }
        rows.addChild(buttonRow);

        rowHelper.addChild(section, 2);
    }

    @Unique
    private static void boundedWorlds$select(WorldSize size, Map<WorldSize, Button> buttons) {
        WorldSizeSelection.setPending(size);
        WorldSizeUi.clearMissingSelection();
        buttons.forEach((tier, button) -> button.active = tier != size);
    }
}
