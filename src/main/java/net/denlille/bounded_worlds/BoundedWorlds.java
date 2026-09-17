package net.denlille.bounded_worlds;

import com.mojang.logging.LogUtils;
import net.denlille.bounded_worlds.config.ModConfigs;
import net.denlille.bounded_worlds.config.RequirementsConfig;
import net.denlille.bounded_worlds.event.WorldBorderHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(BoundedWorlds.MODID)
public class BoundedWorlds {

    public static final String MODID = "bounded_worlds";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BoundedWorlds() {
        // Runs before Forge loads/corrects the TOML, while pre-0.6.0 keys are still readable
        RequirementsConfig.migrateIfNeeded();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ModConfigs.SPEC);
        MinecraftForge.EVENT_BUS.register(new WorldBorderHandler());
    }
}
