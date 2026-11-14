package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.StructureSearchWorker", remap=false)
public abstract class EnhanceCompassPosMixin {
  @Inject(method="confirmStructureAt(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/structure/Structure;I)Lnet/minecraft/core/BlockPos;",
      at=@At("RETURN"), remap=false)
  private void magnet$confirmed(ChunkPos chunk, Structure structure, int radius,
                                CallbackInfoReturnable<BlockPos> ci) {
    // 这个返回值只确认传入的结构，起始区块取确认参数而不是显示坐标。
    CompassTargets.candidate(CompassTargets.field(this, "context"), chunk, ci.getReturnValue(), structure);
  }
}
