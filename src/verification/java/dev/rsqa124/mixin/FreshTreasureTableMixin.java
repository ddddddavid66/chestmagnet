package dev.rsqa124.mixin;

import dev.rsqa124.FreshTreasureTrace;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RandomizableContainerBlockEntity.class, remap = false)
public abstract class FreshTreasureTableMixin {
  @org.spongepowered.asm.mixin.Unique
  private boolean freshTreasureRecorded;
  @org.spongepowered.asm.mixin.Unique
  private int freshTreasureDepth;

  @Inject(method = {"setLootTable(Lnet/minecraft/resources/ResourceLocation;J)V",
      "m_59626_(Lnet/minecraft/resources/ResourceLocation;J)V"}, at = @At("RETURN"))
  private void freshTable(ResourceLocation table, long seed, CallbackInfo ci) {
    FreshTreasureTrace.mark((RandomizableContainerBlockEntity)(Object)this, table);
  }

  @Inject(method = {"unpackLootTable", "m_59640_"}, at = @At("HEAD"))
  private void freshUnpack(Player player, CallbackInfo ci) {
    var be = (RandomizableContainerBlockEntity)(Object)this;
    if (!FreshTreasureTrace.watching(be.getBlockPos()) || freshTreasureDepth++ > 0) return;
    freshTreasureRecorded = FreshTreasureTrace.TABLE.equals(FreshTreasureTrace.rawTable(be));
    if (freshTreasureRecorded) FreshTreasureTrace.unpack(be, "UNPACK_BEGIN");
  }

  @Inject(method = {"unpackLootTable", "m_59640_"}, at = @At("RETURN"))
  private void freshUnpacked(Player player, CallbackInfo ci) {
    if (freshTreasureDepth > 0 && --freshTreasureDepth == 0 && freshTreasureRecorded) {
      FreshTreasureTrace.unpack((RandomizableContainerBlockEntity)(Object)this, "UNPACK_RETURN");
      freshTreasureRecorded = false;
    }
  }
}
