package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.SearchContext", remap=false)
public abstract class EnhanceCompassContextMixin {
  @Inject(method="<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/BlockPos;Ljava/util/List;ZZ)V",
      at=@At("RETURN"), remap=false)
  private void magnet$attach(CallbackInfo ci) {
    if (CompassTargets.field(this, "player") instanceof ServerPlayer player)
      CompassTargets.attach(this, (ServerLevel) CompassTargets.field(this, "level"),
          player, (ItemStack) CompassTargets.field(this, "stack"));
  }

  @Inject(method="reportLocated", at=@At(value="INVOKE", shift=At.Shift.AFTER, remap=false,
      target="Lcom/chaosthedude/explorerscompass/items/ExplorersCompassItem;succeed(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;ZIIILnet/minecraft/resources/ResourceLocation;Ljava/util/List;IZ)V"),
      remap=false)
  private void magnet$located(BlockPos pos, ResourceLocation id, int samples, CallbackInfo ci) {
    if (CompassTargets.field(this, "player") instanceof ServerPlayer player)
      CompassTargets.structureResult(this, (ServerLevel) CompassTargets.field(this, "level"),
          player, pos, null, id, true);
  }

  @Inject(method="reportLocated", at=@At("TAIL"), remap=false)
  private void magnet$noCompass(BlockPos pos, ResourceLocation id, int samples, CallbackInfo ci) {
    if (CompassTargets.field(this, "player") instanceof ServerPlayer player)
      CompassTargets.stopped(this, player, "NOT_FOUND");
  }

  @Inject(method="reportNotFound", at=@At("HEAD"), remap=false)
  private void magnet$notFound(int radius, int samples, CallbackInfo ci) {
    if (CompassTargets.field(this, "player") instanceof ServerPlayer player)
      CompassTargets.stopped(this, player, "NOT_FOUND");
  }

}
