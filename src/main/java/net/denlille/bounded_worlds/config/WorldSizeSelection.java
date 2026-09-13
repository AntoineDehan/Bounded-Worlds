package net.denlille.bounded_worlds.config;

import javax.annotation.Nullable;

/**
 * Hands the world size picked in the creation screen over to the server-start
 * handler. The screen updates {@code pending} while open; clicking "Create New
 * World" arms it, and the handler consumes it exactly once for the world being
 * created. On dedicated servers nothing ever arms, so the configured
 * {@code defaultWorldSize} applies.
 */
public final class WorldSizeSelection {

    // No default: the player must actively pick a size in the screen. A null
    // pending blocks "Create New World" (see CreateWorldScreenMixin); dedicated
    // servers never set it and use the defaultWorldSize config instead.
    @Nullable
    private static volatile WorldSize pending;
    @Nullable
    private static volatile WorldSize armed;

    private WorldSizeSelection() {}

    /** Clears the pending choice when the creation screen builds its World tab. */
    public static void resetPending() {
        pending = null;
    }

    public static void setPending(WorldSize size) {
        pending = size;
    }

    @Nullable
    public static WorldSize pending() {
        return pending;
    }

    /** Called when the player actually clicks "Create New World". */
    public static void armPending() {
        armed = pending;
    }

    /** The armed selection for the world now starting, or null; clears it. */
    @Nullable
    public static WorldSize consumeArmed() {
        WorldSize size = armed;
        armed = null;
        return size;
    }
}
