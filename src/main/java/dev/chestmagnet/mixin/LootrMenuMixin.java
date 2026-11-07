package dev.chestmagnet.mixin;

import dev.chestmagnet.LootrBridge;
import java.util.OptionalInt;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "noobanidus.mods.lootr.util.ChestUtil", remap = false)
public abstract class LootrMenuMixin {
  // 同时列出开发名和发行版 SRG 名，避免依赖第三方模组的 refmap。
  @Group(name = "menu", min = 3, max = 3)
  @Redirect(
      method = {"handleLootChest", "handleLootInventory", "handleLootCart"},
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
              ordinal = 1,
              remap = false),
      require = 0)
  private static OptionalInt devMenu(Player p, MenuProvider provider) {
    return route(p, provider);
  }

  @Group(name = "menu", min = 3, max = 3)
  @Redirect(
      method = {"handleLootChest", "handleLootInventory", "handleLootCart"},
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/player/Player;m_5893_(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
              ordinal = 1,
              remap = false),
      require = 0)
  private static OptionalInt prodMenu(Player p, MenuProvider provider) {
    return route(p, provider);
  }

  @Unique
  private static OptionalInt route(Player p, MenuProvider provider) {
    var c = LootrBridge.ACTIVE.get();
    if (c != null && c.player == p) {
      if (provider instanceof Container inventory) c.inventory = inventory;
      return OptionalInt.empty();
    }
    return p.openMenu(provider);
  }
}
