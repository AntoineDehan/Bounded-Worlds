package net.denlille.bounded_worlds.config;

/** World size tier selected in the world-creation screen; radii come from config. */
public enum WorldSize {
    SMALL,
    MEDIUM,
    LARGE;

    /** The configured radius in blocks for this tier. */
    public int radius() {
        return switch (this) {
            case SMALL -> ModConfigs.SMALL_RADIUS.get();
            case MEDIUM -> ModConfigs.MEDIUM_RADIUS.get();
            case LARGE -> ModConfigs.LARGE_RADIUS.get();
        };
    }

    public String translationKey() {
        return "bounded_worlds.createWorld.worldSize." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
