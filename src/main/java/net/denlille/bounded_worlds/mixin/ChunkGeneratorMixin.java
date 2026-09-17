package net.denlille.bounded_worlds.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;

/**
 * Clamps /locate's spiral search to the world border. Vanilla walks up to 100
 * regions of spacing outward (~128k blocks for mansions) and only stops early
 * on a hit; in a bounded world nothing outside the border can ever match (the
 * border biome ring repaints everything out there), so a miss froze the server
 * thread for minutes. Regions that cannot intersect the border are pointless —
 * on vanilla-sized borders the clamp is a no-op.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @ModifyVariable(
            method = "getNearestGeneratedStructure(Ljava/util/Set;Lnet/minecraft/world/level/LevelReader;"
                    + "Lnet/minecraft/world/level/StructureManager;IIIZJ"
                    + "Lnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadStructurePlacement;)"
                    + "Lcom/mojang/datafixers/util/Pair;",
            at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 0)
    private static int boundedWorlds$clampLocateRadius(int radius, Set<Holder<Structure>> structures,
                                                       LevelReader level, StructureManager structureManager,
                                                       int centerChunkX, int centerChunkZ, int sameRadius,
                                                       boolean skipKnown, long seed,
                                                       RandomSpreadStructurePlacement placement) {
        WorldBorder border = level.getWorldBorder();
        double centerBlockX = centerChunkX * 16.0;
        double centerBlockZ = centerChunkZ * 16.0;
        // The spiral walks square rings — Chebyshev distance to the farthest
        // border corner bounds the regions that can still hold a valid cell
        double maxBlocks = Math.max(
                Math.max(Math.abs(border.getMinX() - centerBlockX), Math.abs(border.getMaxX() - centerBlockX)),
                Math.max(Math.abs(border.getMinZ() - centerBlockZ), Math.abs(border.getMaxZ() - centerBlockZ)));
        int regions = (int) (maxBlocks / 16.0) / placement.spacing() + 2;
        return Math.min(radius, regions);
    }
}
