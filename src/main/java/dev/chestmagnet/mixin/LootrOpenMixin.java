package dev.chestmagnet.mixin;

import dev.chestmagnet.LootrOpenScope;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "noobanidus.mods.lootr.common.impl.DefaultLootrAPIImpl", remap = false)
abstract class LootrOpenMixin {
  @Redirect(method = {
      "handleProviderOpen(Lnoobanidus/mods/lootr/common/api/data/ILootrInfoProvider;Lnet/minecraft/server/level/ServerPlayer;Lnoobanidus/mods/lootr/common/api/MenuBuilder;)V",
      "handleProviderOpen(Lnoobanidus/mods/lootr/common/api/data/ILootrInfoProvider;Lnet/minecraft/server/level/ServerPlayer;Lnoobanidus/mods/lootr/common/api/MenuBuilder;Z)V"
  }, at = @At(value = "INVOKE",
      target = "Lnet/minecraft/server/level/ServerPlayer;openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
      ordinal = 1), require = 1, expect = 1, allow = 1)
  private OptionalInt chestmagnet$openMenu(ServerPlayer player, MenuProvider menu) {
    return LootrOpenScope.openMenu(player, menu);
  }
}
