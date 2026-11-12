package dev.rsqa124.mixin;

import dev.rsqa124.BenchCounters;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "dev.chestmagnet.Engine", remap = false)
public abstract class EngineCountMixin {
  @Redirect(method = "work", at = @At(value = "INVOKE",
      target = "Lnet/minecraftforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;"),
      remap = false)
  private ItemStack countProbe(IItemHandler handler, int slot, int count, boolean simulate) {
    if (simulate) BenchCounters.probe();
    return handler.extractItem(slot, count, simulate);
  }
}
