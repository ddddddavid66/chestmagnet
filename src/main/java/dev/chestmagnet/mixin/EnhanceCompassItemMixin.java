package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.items.ExplorersCompassItem", remap=false)
public abstract class EnhanceCompassItemMixin {
  @Inject(method="setSearching", at=@At("TAIL"), remap=false)
  private void magnet$search(ItemStack stack, @Coerce Object target, ResourceLocation key, Player p, CallbackInfo ci) {
    if (p instanceof ServerPlayer player) CompassTargets.begin(player, stack);
  }

  @Inject(method="fail", at=@At("TAIL"), remap=false)
  private void magnet$fail(Player p, ItemStack stack, int radius, int samples, CallbackInfo ci) {
    if (p instanceof ServerPlayer player) CompassTargets.failed(stack, player);
  }

  @Inject(method="cancelSearch", at=@At("TAIL"), remap=false)
  private void magnet$cancel(Level level, Player p, ItemStack stack, CallbackInfo ci) {
    if (!level.isClientSide && p instanceof ServerPlayer player) CompassTargets.cancel(stack, player);
  }
}
