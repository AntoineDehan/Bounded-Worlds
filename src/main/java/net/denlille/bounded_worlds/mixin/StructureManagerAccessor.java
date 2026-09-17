package net.denlille.bounded_worlds.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the StructureCheck so force-placed starts can refresh its cache —
 * /locate and later counts would otherwise read a stale "not present" entry.
 */
@Mixin(StructureManager.class)
public interface StructureManagerAccessor {

    @Accessor("structureCheck")
    StructureCheck boundedWorlds$structureCheck();
}
