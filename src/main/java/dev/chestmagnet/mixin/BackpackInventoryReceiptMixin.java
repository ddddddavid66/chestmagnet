package dev.chestmagnet.mixin;

import dev.chestmagnet.BackpackReceipt;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
abstract class BackpackInventoryReceiptMixin {
  @Shadow @Final private IStorageWrapper storageWrapper;
  @Shadow public abstract ItemStack getSlotStack(int slot);

  @Inject(method = "insertItemInternal", at = @At("HEAD"), require = 0)
  private void chestmagnet$observe(int slot, ItemStack input, boolean simulate, CallbackInfoReturnable<ItemStack> ci) {
    if (!simulate && BackpackReceipt.active())
      BackpackReceipt.beforeSlot(this, storageWrapper, slot, input, false, this::getSlotStack);
  }

  @Inject(method = "extractItemInternal", at = @At("HEAD"), require = 0)
  private void chestmagnet$observeExtraction(int slot, int count, boolean simulate, CallbackInfoReturnable<ItemStack> ci) {
    if (!simulate && BackpackReceipt.active())
      BackpackReceipt.beforeChange(this, storageWrapper, slot, this::getSlotStack);
  }

  @Inject(method = "setSlotStack", at = @At("HEAD"), require = 0)
  private void chestmagnet$observeReplacement(int slot, ItemStack stack, CallbackInfo ci) {
    if (BackpackReceipt.active())
      BackpackReceipt.beforeChange(this, storageWrapper, slot, this::getSlotStack);
  }
}
