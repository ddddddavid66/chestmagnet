package dev.chestmagnet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.*;

public final class Destinations {
  public interface Target {
    ItemStack insert(ItemStack stack, boolean simulate);

    default boolean unavailable() {
      return false;
    }

    default String reason() {
      return "目的地已满或拒收，请切换背包/物品栏";
    }

    default boolean loops(Sources.Ref source, ServerPlayer p) {
      return false;
    }
  }

  public static Target get(ServerPlayer p, int mode) {
    if (mode == 1)
      return ModList.get().isLoaded("refinedstorage")
          ? RsCompat.target(p)
          : failed("未安装 RS，请切换背包/物品栏");
    if (mode == 2)
      return ModList.get().isLoaded("sophisticatedbackpacks")
          ? BackpackCompat.target(p)
          : failed("未安装精妙背包，请切换物品栏");
    return new Target() {
      public ItemStack insert(ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2; pass++)
          for (int i = 0; i < 36 && !remaining.isEmpty(); i++) {
            var current = p.getInventory().getItem(i);
            if ((pass == 0 && current.isEmpty()) || (pass == 1 && !current.isEmpty())) continue;
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, remaining)) continue;
            int room =
                current.isEmpty()
                    ? remaining.getMaxStackSize()
                    : current.getMaxStackSize() - current.getCount();
            int n = Math.min(room, remaining.getCount());
            if (n <= 0) continue;
            if (!simulate) {
              if (current.isEmpty()) p.getInventory().setItem(i, remaining.copyWithCount(n));
              else current.grow(n);
              p.getInventory().setChanged();
            }
            remaining.shrink(n);
          }
        return remaining;
      }
    };
  }

  public static Target failed(String reason) {
    return new Target() {
      public ItemStack insert(ItemStack s, boolean sim) {
        return s;
      }

      public boolean unavailable() {
        return true;
      }

      public String reason() {
        return reason;
      }
    };
  }
}
