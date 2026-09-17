package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.config.CompassDirection;

/**
 * Computes directional climate biases based on block position.
 * Initialized once during server start, then read by worldgen threads via ClimateSamplerMixin.
 *
 * Thread-safety: all fields are written once before worldgen threads start (write-once pattern).
 */
public class DirectionalClimateManager {

    // Set once at startup, never modified during worldgen
    private static volatile boolean enabled = false;
    private static int centerX;
    private static int centerZ;
    private static int worldRadius;
    private static int hotDx;
    private static int hotDz;
    private static int humidDx;
    private static int humidDz;

    // Maximum bias strength (out of typical Climate range of ~[-1, 1] as floats)
    // 0.5 means vanilla noise still dominates, direction is a "nudge"
    private static final float MAX_BIAS = 0.5f;

    /**
     * Initialize the climate manager. Called once during server start.
     */
    public static void init(DirectionalPlacement placement, int cx, int cz, int radius) {
        centerX = cx;
        centerZ = cz;
        worldRadius = radius;

        CompassDirection hotDir = placement.getHotDirection();
        hotDx = hotDir.getDx();
        hotDz = hotDir.getDz();

        CompassDirection humidDir = placement.getHumidDirection();
        humidDx = humidDir.getDx();
        humidDz = humidDir.getDz();

        enabled = true;
    }

    /**
     * Clear the manager (on server stop or restart).
     */
    public static void clear() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a temperature bias for the given block position.
     * Positive = hotter, Negative = colder.
     * Uses a quadratic ramp: zero at spawn, increasing toward edges.
     *
     * @return bias as a float, suitable for adding to Climate's float-range values
     */
    public static float getTemperatureBias(int blockX, int blockZ) {
        if (!enabled) return 0;
        return computeBias(blockX, blockZ, hotDx, hotDz);
    }

    /**
     * Returns a humidity bias for the given block position.
     * Positive = more humid, Negative = drier.
     */
    public static float getHumidityBias(int blockX, int blockZ) {
        if (!enabled) return 0;
        return computeBias(blockX, blockZ, humidDx, humidDz);
    }

    /**
     * Project position onto a direction axis and compute a quadratic bias.
     *
     * The bias is 0 at spawn center and ramps up to MAX_BIAS at the world border.
     * Quadratic ramp (x * |x|) ensures the center ~20-30% of the world has nearly zero bias,
     * letting vanilla noise dominate near spawn while the edges are strongly biased.
     */
    private static float computeBias(int blockX, int blockZ, int dirDx, int dirDz) {
        int relX = blockX - centerX;
        int relZ = blockZ - centerZ;

        // Projection onto the direction vector, normalized to [-1, 1] over the radius
        int projection = relX * dirDx + relZ * dirDz;
        float normalized = (float) projection / worldRadius;
        normalized = Math.max(-1f, Math.min(1f, normalized));

        // Quadratic ramp: x * |x| preserves sign but grows slowly near center
        return normalized * Math.abs(normalized) * MAX_BIAS;
    }
}
