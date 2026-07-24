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

        // No constraint for fully neutral biomes
        if (temp == BiomeClimateClassifier.TemperatureCategory.TEMPERATE
                && humidity == BiomeClimateClassifier.HumidityCategory.NEUTRAL) {
            return true;
        }

        // Within the neutral zone around spawn → no constraint
        double dx = blockX - centerX;
        double dz = blockZ - centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double neutralRadius = worldRadius * NEUTRAL_ZONE_FRACTION;
        if (dist < neutralRadius) {
            return true;
        }

        // Check temperature axis constraint
        boolean tempOk = checkAxis(blockX, blockZ, centerX, centerZ, temp);

        // Check humidity axis constraint
        boolean humidOk = checkAxis(blockX, blockZ, centerX, centerZ, humidity);

        return tempOk && humidOk;
    }

    /**
     * Check temperature axis: block must be in the correct half-plane.
     */
    private boolean checkAxis(int blockX, int blockZ, int centerX, int centerZ,
                               BiomeClimateClassifier.TemperatureCategory temp) {
        if (temp == BiomeClimateClassifier.TemperatureCategory.TEMPERATE) {
            return true; // No temperature constraint
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
            return true; // No humidity constraint
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

        // Project onto the direction vector
        // For NORTH (0,-1): projection = -relZ → must be > 0 → relZ < 0
        // For SOUTH (0,1): projection = relZ → must be > 0 → relZ > 0
        // For EAST (1,0): projection = relX → must be > 0 → relX > 0
        // For WEST (-1,0): projection = -relX → must be > 0 → relX < 0
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
        // Start with full circle
        double minAngle = 0;
        double maxAngle = 2 * Math.PI;

        // Constrain by temperature
        if (temp != BiomeClimateClassifier.TemperatureCategory.TEMPERATE) {
            CompassDirection dir = (temp == BiomeClimateClassifier.TemperatureCategory.HOT)
                    ? hotDirection : coldDirection;
            double[] halfPlane = directionToAngleRange(dir);
            minAngle = halfPlane[0];
            maxAngle = halfPlane[1];
        }

        // Constrain by humidity (intersect with temperature range)
        if (humidity != BiomeClimateClassifier.HumidityCategory.NEUTRAL) {
            CompassDirection dir = (humidity == BiomeClimateClassifier.HumidityCategory.HUMID)
                    ? humidDirection : dryDirection;
            double[] halfPlane = directionToAngleRange(dir);

            if (temp != BiomeClimateClassifier.TemperatureCategory.TEMPERATE) {
                // Intersect: take the quadrant
                minAngle = Math.max(minAngle, halfPlane[0]);
                maxAngle = Math.min(maxAngle, halfPlane[1]);
            } else {
                minAngle = halfPlane[0];
                maxAngle = halfPlane[1];
            }
        }

        return new double[]{minAngle, maxAngle};
    }

    /**
     * Converts a compass direction to an angle range (half-plane).
     * Minecraft: +X = east, +Z = south.
     * Angle 0 = east, PI/2 = south, PI = west, 3PI/2 = north.
     */
    private static double[] directionToAngleRange(CompassDirection dir) {
        return switch (dir) {
            case EAST -> new double[]{-Math.PI / 2, Math.PI / 2};       // -90° to 90°
            case SOUTH -> new double[]{0, Math.PI};                       // 0° to 180°
            case WEST -> new double[]{Math.PI / 2, 3 * Math.PI / 2};    // 90° to 270°
            case NORTH -> new double[]{Math.PI, 2 * Math.PI};            // 180° to 360°
            default -> new double[]{0, 2 * Math.PI};                      // full circle
        };
    }
}
