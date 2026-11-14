package dev.chestmagnet.mixin;

import com.mojang.datafixers.util.Pair;
import dev.chestmagnet.CompassTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.StructureSearchWorker", remap=false)
public abstract class OriginalCompassWorkerMixin {
  private static final String SUCCESS = "succeed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)V";
  private static final String ITEM = "Lcom/chaosthedude/explorerscompass/items/ExplorersCompassItem;";
  private static final String SHORT_SUCCESS = "succeed(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;IIIZ)V";
  private static final String LONG_SUCCESS = "succeed(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;ZIILjava/util/List;IZ)V";
  @Inject(method="<init>", at=@At("RETURN"), remap=false)
  private void magnet$attach(CallbackInfo ci) {
    if (CompassTargets.field(this,"player") instanceof ServerPlayer player)
      CompassTargets.attach(this, (ServerLevel) CompassTargets.field(this,"level"),
          player, (ItemStack) CompassTargets.field(this,"stack"));
  }

  @Inject(method="getStructureGeneratingAt", at=@At("RETURN"), remap=false)
  private void magnet$candidate(ChunkPos chunk, CallbackInfoReturnable<Pair<BlockPos, Structure>> ci) {
    CompassTargets.candidate(this, chunk, ci.getReturnValue());
  }

  @Group(name="magnet$nativeSuccess", min=1, max=1)
  @Inject(method=SUCCESS, at=@At(value="INVOKE", target=ITEM + SHORT_SUCCESS, shift=At.Shift.AFTER), require=0, remap=false)
  private void magnet$shortSuccess(BlockPos pos, Structure structure, CallbackInfo ci) {
    magnet$publish(pos, structure);
  }

  @Group(name="magnet$nativeSuccess", min=1, max=1)
  @Inject(method=SUCCESS, at=@At(value="INVOKE", target=ITEM + LONG_SUCCESS, shift=At.Shift.AFTER), require=0, remap=false)
  private void magnet$longSuccess(BlockPos pos, Structure structure, CallbackInfo ci) {
    magnet$publish(pos, structure);
  }

  @org.spongepowered.asm.mixin.Unique
  private void magnet$publish(BlockPos pos, Structure structure) {
    // 单个 worker 失败可能启动下一个；只有物品的终态回调处理全局失败。
    if (CompassTargets.field(this,"player") instanceof ServerPlayer player)
      CompassTargets.structureResult(this, (ServerLevel) CompassTargets.field(this,"level"),
          player, pos, structure, null, false);
  }

}
