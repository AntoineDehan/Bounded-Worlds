package net.denlille.bounded_worlds.config;

import net.denlille.bounded_worlds.BoundedWorlds;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All mod config lives in config/bounded_worlds/ (common.toml + requirements.json).
 * Creates the folder before Forge loads the TOML (Forge does not create config
 * subdirectories itself) and moves files from the old flat layouts in place —
 * a move keeps every user value, including configVersion.
 */
public final class ConfigFolder {

    public static final String FOLDER_NAME = "bounded_worlds";

    private ConfigFolder() {}

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve(FOLDER_NAME);
    }

    /** Called first in the mod constructor, before any config registration or migration. */
    public static void setup() {
        try {
            Files.createDirectories(folder());
        } catch (Exception e) {
            BoundedWorlds.LOGGER.error("[Bounded Worlds] Could not create config/{}: {}", FOLDER_NAME, e.getMessage());
            return;
        }
        moveLegacy("bounded_worlds-common.toml", "common.toml");
        moveLegacy("bounded_worlds-requirements.json", "requirements.json");
    }

    private static void moveLegacy(String oldName, String newName) {
        Path oldPath = FMLPaths.CONFIGDIR.get().resolve(oldName);
        Path newPath = folder().resolve(newName);
        if (!Files.exists(oldPath) || Files.exists(newPath)) {
            return;
        }
        try {
            Files.move(oldPath, newPath);
            BoundedWorlds.LOGGER.info("[Bounded Worlds] Moved config/{} to config/{}/{}.", oldName, FOLDER_NAME, newName);
        } catch (Exception e) {
            BoundedWorlds.LOGGER.error("[Bounded Worlds] Could not move config/{} into config/{}: {}",
                    oldName, FOLDER_NAME, e.getMessage());
        }
    }
}
