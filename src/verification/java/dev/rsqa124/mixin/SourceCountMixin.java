package dev.rsqa124.mixin;

import dev.rsqa124.BenchCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.chestmagnet.Sources", remap = false)
public abstract class SourceCountMixin {
  @Inject(method = "eligible", at = @At("HEAD"), remap = false)
  private static void countCandidate(Object value, CallbackInfoReturnable<Boolean> result) {
    BenchCounters.candidate();
  }
  @Inject(method="inventory(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraftforge/items/IItemHandler;",at=@At("RETURN"),remap=false)
  private static void originalObserved(net.minecraft.server.level.ServerPlayer p,Object source,net.minecraft.world.item.ItemStack magnet,CallbackInfoReturnable<net.minecraftforge.items.IItemHandler> result){
    if(!System.getProperty("chestmagnet.repair128", "").equals("buried-original"))return;
    if(!(source instanceof net.minecraft.world.level.block.entity.BlockEntity be) || !be.getBlockPos().equals(dev.rsqa124.OriginalBuriedProbe.at) || result.getReturnValue()==null)return;
    int n=0;var inv=result.getReturnValue();for(int i=0;i<inv.getSlots();i++)n+=inv.getStackInSlot(i).getCount();
    dev.rsqa124.OriginalBuriedProbe.seen=true;dev.rsqa124.OriginalBuriedProbe.remaining=n;
  }
}
