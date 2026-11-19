package dev.chestmagnet;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.block.entities.LootrInventoryBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.data.DataStorage;
import noobanidus.mods.lootr.data.SpecialChestInventory;
import noobanidus.mods.lootr.entity.LootrChestMinecartEntity;

final class LootrCompat {
  static boolean supports(Object source) {
    return source instanceof LootrChestMinecartEntity
        || source instanceof RandomizableContainerBlockEntity && source instanceof ILootBlockEntity;
  }

  static boolean awaitingConversion(RandomizableContainerBlockEntity tile, ServerLevel level) {
    if (ConfigManager.DISABLE.get() || ConfigManager.isDimensionBlocked(level.dimension())
        || ConfigManager.CHECK_WORLD_BORDER.get() && !level.getWorldBorder().isWithinBounds(tile.getBlockPos()))
      return false;
    var table = UnopenedLoot.table(tile);
    return table != null && !ConfigManager.isBlacklisted(table)
        && ConfigManager.replacement(tile.getBlockState()) != null;
  }

  static Container inventory(ServerPlayer player, Object source, boolean collectLocked) {
    var level = player.serverLevel();
    if (source instanceof LootrChestMinecartEntity cart) {
      if (!cart.isAlive() || level.getEntity(cart.getUUID()) != cart || !usable(cart.getUUID())) return null;
      var inventory = DataStorage.getInventory(level, cart, player, cart::addLoot);
      return inventory != null && inventory.getEntity(level) == cart ? inventory : null;
    }
    if (!(source instanceof RandomizableContainerBlockEntity tile)
        || !(source instanceof ILootBlockEntity lootr) || tile.isRemoved()
        || level.getChunkSource().getChunkNow(tile.getBlockPos().getX() >> 4, tile.getBlockPos().getZ() >> 4) == null
        || level.getBlockEntity(tile.getBlockPos()) != tile
        || (!collectLocked && !SourceExtras.openableWithHeldKey(tile, player))) return null;
    var id = lootr.getTileId();
    if (!usable(id)) return null;

    // 只解析当前玩家的原生库存，不触发开箱、衰减计时或全体玩家刷新。
    SpecialChestInventory inventory = tile instanceof LootrInventoryBlockEntity custom
        ? DataStorage.getInventory(level, id, custom.getCustomInventory(), player, tile.getBlockPos(), tile)
        : DataStorage.getInventory(level, id, tile.getBlockPos(), player, tile, lootr::unpackLootTable);
    return inventory != null && id.equals(inventory.getTileId())
        && tile.getBlockPos().equals(inventory.getPos()) ? inventory : null;
  }

  private static boolean usable(UUID id) {
    return id != null && !DataStorage.isDecayed(id) && !DataStorage.isRefreshed(id);
  }
}
