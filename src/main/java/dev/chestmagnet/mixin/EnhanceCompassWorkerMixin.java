package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.StructureSearchWorker", remap=false)
public abstract class EnhanceCompassWorkerMixin {
  @Inject(method="getStructureGeneratingAt", at=@At("RETURN"), remap=false)
  private void magnet$confirmedSync(ChunkPos chunk, CallbackInfoReturnable<Pair<BlockPos, Structure>> ci) {
    if (ci.getReturnValue() != null)
      CompassTargets.candidate(CompassTargets.field(this, "context"), chunk, ci.getReturnValue());
  }

}
