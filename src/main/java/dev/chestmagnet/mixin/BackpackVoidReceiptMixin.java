package dev.chestmagnet.mixin;

import dev.chestmagnet.BackpackReceipt;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IItemHandlerSimpleInserter;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper", remap = false)
abstract class BackpackVoidReceiptMixin {
  @Unique private int chestmagnet$before;

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), require = 0)
  private void chestmagnet$beforeSlot(InventoryHandler inv, int slot, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$start(input, sim); }

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"), require = 0)
  private void chestmagnet$afterSlot(InventoryHandler inv, int slot, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$finish(ci.getReturnValue()); }

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), require = 0)
  private void chestmagnet$beforeAlways(InventoryHandler inv, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$start(input, sim); }

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"), require = 0)
  private void chestmagnet$afterAlways(InventoryHandler inv, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$finish(ci.getReturnValue()); }

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/IItemHandlerSimpleInserter;ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), require = 0)
  private void chestmagnet$beforeLegacy(IItemHandlerSimpleInserter inv, int slot, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$start(input, sim); }

  @Inject(method = "onBeforeInsert(Lnet/p3pp3rf1y/sophisticatedcore/inventory/IItemHandlerSimpleInserter;ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"), require = 0)
  private void chestmagnet$afterLegacy(IItemHandlerSimpleInserter inv, int slot, ItemStack input, boolean sim,
      CallbackInfoReturnable<ItemStack> ci) { chestmagnet$finish(ci.getReturnValue()); }

  @Inject(method = {"onOverflow", "onSlotOverflow", "onStorageOverflow"}, at = @At("HEAD"), require = 0)
  private void chestmagnet$beforeOverflow(ItemStack input, CallbackInfoReturnable<ItemStack> ci) {
    chestmagnet$start(input, false);
  }

  @Inject(method = {"onOverflow", "onSlotOverflow", "onStorageOverflow"}, at = @At("RETURN"), require = 0)
  private void chestmagnet$afterOverflow(ItemStack input, CallbackInfoReturnable<ItemStack> ci) {
    chestmagnet$finish(ci.getReturnValue());
  }

  @Unique private void chestmagnet$start(ItemStack input, boolean simulate) {
    chestmagnet$before = BackpackReceipt.active() && this instanceof BackpackUpgradeOwnerMixin owner
        ? BackpackReceipt.beforeVoid(owner.chestmagnet$owner(), input, simulate) : 0;
  }

  @Unique private void chestmagnet$finish(ItemStack output) {
    if (chestmagnet$before != 0) {
      BackpackReceipt.afterVoid(chestmagnet$before, output);
      chestmagnet$before = 0;
    }
  }
}
