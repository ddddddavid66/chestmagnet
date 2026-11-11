package dev.chestmagnet.mixin;

import dev.chestmagnet.LootCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Block.class, remap = false)
public class DropCaptureMixin {
  @Inject(
      method = {
        "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        "m_49840_(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V"
      },
      at = @At("HEAD"),
      cancellable = true,
      remap = false)
  private static void capture(Level l, BlockPos p, ItemStack s, CallbackInfo ci) {
    // 破坏事件可能递归处理其他方块；只能接管本次来源坐标的产物。
    if (LootCapture.add(l, p, s)) ci.cancel();
  }
}
