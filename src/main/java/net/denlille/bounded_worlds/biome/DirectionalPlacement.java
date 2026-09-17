package net.denlille.bounded_worlds.biome;

import net.denlille.bounded_worlds.config.CompassDirection;

/**
 * Determines which region of the world a biome should be placed in,
 * based on its temperature and humidity classification.
 *
 * Two perpendicular axes divide the world into 4 quadrants around spawn:
 * - Temperature axis: hot direction ↔ cold direction (opposite)
 * - Humidity axis: humid direction ↔ dry direction (opposite)
 *
 * A neutral zone around spawn (15% of world radius) has no constraints.
 */
public class DirectionalPlacement {

    private static final double NEUTRAL_ZONE_FRACTION = 0.15;

    private final CompassDirection hotDirection;
    private final CompassDirection coldDirection;
    private final CompassDirection humidDirection;
    private final CompassDirection dryDirection;

    public DirectionalPlacement(CompassDirection hotDirection, CompassDirection humidDirection) {
        this.hotDirection = hotDirection;
        this.coldDirection = hotDirection.opposite();
        this.humidDirection = humidDirection;
        this.dryDirection = humidDirection.opposite();
    }

    public CompassDirection getHotDirection() {
        return hotDirection;
    }

    public CompassDirection getHumidDirection() {
        return humidDirection;
    }

    /**
     * Checks if a block position is in the correct region for the given climate classification.
     * TEMPERATE+NEUTRAL biomes have no constraint.
     * Positions within the neutral zone (15% of radius from center) have no constraint.
     */
    public boolean isInCorrectRegion(int blockX, int blockZ, int centerX, int centerZ,
                                      int worldRadius,
                                      BiomeClimateClassifier.TemperatureCategory temp,
                                      BiomeClimateClassifier.HumidityCategory humidity) {

        if (temp == BiomeClimateClassifier.TemperatureCategory.TEMPERATE
                && humidity == BiomeClimateClassifier.HumidityCategory.NEUTRAL) {
            return true;
        }

        double dx = blockX - centerX;
        double dz = blockZ - centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double neutralRadius = worldRadius * NEUTRAL_ZONE_FRACTION;
        if (dist < neutralRadius) {
            return true;
        }

        return checkAxis(blockX, blockZ, centerX, centerZ, temp)
                && checkAxis(blockX, blockZ, centerX, centerZ, humidity);
    }

    /**
     * Check temperature axis: block must be in the correct half-plane.
     */
    private boolean checkAxis(int blockX, int blockZ, int centerX, int centerZ,
                               BiomeClimateClassifier.TemperatureCategory temp) {
        if (temp == BiomeClimateClassifier.TemperatureCategory.TEMPERATE) {
            return true;
        }

        CompassDirection targetDir = (temp == BiomeClimateClassifier.TemperatureCategory.HOT)
                ? hotDirection : coldDirection;

        return isInHalfPlane(blockX, blockZ, centerX, centerZ, targetDir);
    }

    /**
     * Check humidity axis: block must be in the correct half-plane.
     */
    private boolean checkAxis(int blockX, int blockZ, int centerX, int centerZ,
                               BiomeClimateClassifier.HumidityCategory humidity) {
        if (humidity == BiomeClimateClassifier.HumidityCategory.NEUTRAL) {
            return true;
        }

        CompassDirection targetDir = (humidity == BiomeClimateClassifier.HumidityCategory.HUMID)
                ? humidDirection : dryDirection;

        return isInHalfPlane(blockX, blockZ, centerX, centerZ, targetDir);
    }

    /**
     * Checks if a position is in the half-plane defined by a compass direction from center.
     * E.g., for SOUTH (dz=1): blockZ must be >= centerZ (positive Z = south in Minecraft).
     */
    private static boolean isInHalfPlane(int blockX, int blockZ, int centerX, int centerZ,
                                          CompassDirection direction) {
        int relX = blockX - centerX;
        int relZ = blockZ - centerZ;

        // Projection onto the direction vector; e.g. SOUTH (0,1) → relZ >= 0
        // (positive Z = south in Minecraft)
        int projection = relX * direction.getDx() + relZ * direction.getDz();
        return projection >= 0;
    }

    /**
     * Returns an angle range (in radians) for the target quadrant/half-plane.
     * Used by fallback placement to constrain random angles.
     *
     * @return double[2] = {minAngle, maxAngle} in radians, where 0 = +X (east), PI/2 = +Z (south)
     */
    public double[] getConstrainedAngleRange(BiomeClimateClassifier.TemperatureCategory temp,
                                              BiomeClimateClassifier.HumidityCategory humidity) {
        boolean hasTemp = temp != BiomeClimateClassifier.TemperatureCategory.TEMPERATE;
        boolean hasHumid = humidity != BiomeClimateClassifier.HumidityCategory.NEUTRAL;

        if (!hasTemp && !hasHumid) {
            return new double[]{0, 2 * Math.PI};
        }

        CompassDirection tempDir = (temp == BiomeClimateClassifier.TemperatureCategory.HOT)
                ? hotDirection : coldDirection;
        CompassDirection humidDir = (humidity == BiomeClimateClassifier.HumidityCategory.HUMID)
                ? humidDirection : dryDirection;

        if (hasTemp && hasHumid) {
            // Quadrant: a quarter circle centered on the bisector of the two
            // (perpendicular) directions. Computed via vector addition so
            // wrap-around ranges (e.g. north+east) are handled correctly.
            double bisector = Math.atan2(tempDir.getDz() + humidDir.getDz(),
                                          tempDir.getDx() + humidDir.getDx());
            return new double[]{bisector - Math.PI / 4, bisector + Math.PI / 4};
        }

        // Single constraint: the half circle centered on that direction
        CompassDirection dir = hasTemp ? tempDir : humidDir;
        double center = Math.atan2(dir.getDz(), dir.getDx());
        return new double[]{center - Math.PI / 2, center + Math.PI / 2};
    }
}
