package dev.chestmagnet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BlockEntity;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;

public final class LootrCompat {
  private LootrCompat() {}

  private static ILootrInfoProvider provider(Object source) {
    if (source instanceof ILootrInfoProvider known) return known;
    if (source instanceof BlockEntity block) return LootrAPI.resolveBlockEntity(block);
    if (source instanceof Entity entity) return LootrAPI.resolveEntity(entity);
    return null;
  }

  static boolean supports(Object source) {
    return provider(source) != null;
  }

  public static Container inventory(ServerPlayer player, Object source) {
    if (source instanceof ItemFrame) return null;
    if (!LootrAPI.isReady() || LootrAPI.isDisabled()) return null;
    var provider = provider(source);
    if (provider == null || !provider.canPlayerOpen(player)
        || (provider.canDecay() && LootrAPI.isDecayed(provider))) return null;
    if (provider.hasServerOpened(player) && !(provider.canRefresh() && LootrAPI.isRefreshed(provider))) {
      return LootrAPI.getInventory(provider, player);
    }
    // 原生初始化个人战利品，并记录已开过；只拦截这一次的个人菜单。
    var menu = LootrOpenScope.openWithoutMenu(player, () -> LootrAPI.handleProviderOpen(provider, player));
    return menu instanceof ILootrInventory inventory ? inventory : null;
  }
}
