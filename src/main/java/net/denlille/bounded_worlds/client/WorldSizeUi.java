package net.denlille.bounded_worlds.client;

import net.minecraft.client.gui.components.StringWidget;

import javax.annotation.Nullable;

/**
 * Client-side state shared between the two creation-screen mixins: whether the
 * "World Size" row was actually injected (if a mod conflict skipped it, world
 * creation must NOT be blocked), and the title widget so a blocked creation can
 * turn it red until the player picks a size.
 */
public final class WorldSizeUi {

    private static final int COLOR_DEFAULT = 0xFFFFFF;
    private static final int COLOR_MISSING = 0xFF5555;

    private static boolean rowPresent;
    @Nullable
    private static StringWidget title;

    private WorldSizeUi() {}

    /** Called when the World tab builds its row; replaces the previous screen's widget. */
    public static void setTitle(StringWidget widget) {
        rowPresent = true;
        title = widget;
    }

    public static boolean isRowPresent() {
        return rowPresent;
    }

    /** Creation was blocked: highlight the title until a size is picked. */
    public static void flagMissingSelection() {
        if (title != null) {
            title.setColor(COLOR_MISSING);
        }
    }

    /** A size was picked: back to the normal title color. */
    public static void clearMissingSelection() {
        if (title != null) {
            title.setColor(COLOR_DEFAULT);
        }
    }
}
