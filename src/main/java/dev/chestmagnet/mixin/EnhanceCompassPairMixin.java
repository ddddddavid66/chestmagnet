package dev.chestmagnet.mixin;

import com.mojang.datafixers.util.Pair;
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
public abstract class EnhanceCompassPairMixin {
  @Inject(method="confirmStructureAt(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/structure/Structure;I)Lcom/mojang/datafixers/util/Pair;",
      at=@At("RETURN"), remap=false)
  private void magnet$confirmed(ChunkPos chunk, Structure predicted, int radius,
                                CallbackInfoReturnable<Pair<BlockPos, Structure>> ci) {
    CompassTargets.candidate(CompassTargets.field(this, "context"), chunk, ci.getReturnValue());
  }
}
