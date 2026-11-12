package dev.chestmagnet;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.block.entities.LootrInventoryBlockEntity;
import noobanidus.mods.lootr.data.*;
import noobanidus.mods.lootr.entity.LootrChestMinecartEntity;
import noobanidus.mods.lootr.util.ChestUtil;

public final class LootrCompat {
  private record Key(UUID player, UUID source) {}

  private static final Map<Key, Boolean> SEEN = new HashMap<>();

  private static final class MenuHook {
    static final boolean READY = verify();

    private static boolean verify() {
      try {
        Class.forName("noobanidus.mods.lootr.util.ChestUtil", false,
            LootrCompat.class.getClassLoader());
        if (LootrBridge.hasMenuHook()) return true;
      } catch (ClassNotFoundException | LinkageError ignored) {
        // 不调用原生打开入口，也不降级读取共享库存。
      }
      org.slf4j.LoggerFactory.getLogger("chestmagnet").warn("Lootr 菜单钩子不兼容，已停用 Lootr 收取");
      return false;
    }
  }

  public static void clear() {
    SEEN.clear();
  }

  public static Container inventory(ServerPlayer p, Object source) {
    if (!MenuHook.READY) return null;
    var level = p.serverLevel();
    boolean cart = source instanceof LootrChestMinecartEntity;
    UUID id =
        cart
            ? ((LootrChestMinecartEntity) source).getUUID()
            : ((ILootBlockEntity) source).getTileId();
    if (id == null || DataStorage.isDecayed(id)) return null;
    var key = new Key(p.getUUID(), id);
    boolean refresh = DataStorage.isRefreshed(id);
    // 首次或刷新才走完整生命周期，避免统计、通知与音效每 tick 重复触发。
    if (!SEEN.containsKey(key) || refresh) {
      Container found =
          LootrBridge.capture(
              p,
              () -> {
                if (cart) ChestUtil.handleLootCart(level, (LootrChestMinecartEntity) source, p);
                else {
                  var be = (BlockEntity) source;
                  if (be instanceof LootrInventoryBlockEntity)
                    ChestUtil.handleLootInventory(
                        be.getBlockState().getBlock(), level, be.getBlockPos(), p);
                  else
                    ChestUtil.handleLootChest(
                        be.getBlockState().getBlock(), level, be.getBlockPos(), p);
                }
              });
      if (found instanceof SpecialChestInventory inv
          && (cart ? inv.getEntity(level) == source : id.equals(inv.getTileId()))) {
        SEEN.put(key, true);
        return found;
      }
      return null;
    }
    if (cart) {
      var c = (LootrChestMinecartEntity) source;
      return DataStorage.getInventory(level, c, p, c::addLoot);
    }
    var be = (RandomizableContainerBlockEntity) source;
    var tile = (ILootBlockEntity) source;
    if (be instanceof LootrInventoryBlockEntity special)
      return DataStorage.getInventory(
          level, id, special.getCustomInventory(), p, be.getBlockPos(), be);
    return DataStorage.getInventory(level, id, be.getBlockPos(), p, be, tile::unpackLootTable);
  }
}
