package dev.chestmagnet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.*;

public final class Destinations {
  public interface Target {
    ItemStack insert(ItemStack stack, boolean simulate);

    /** 任务检测只读；返回足够证明的当前数量，不从库存提取。 */
    default long count(ItemStack key, java.util.function.Predicate<ItemStack> matches,
        long limit, long deadline) {
      return 0;
    }

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

  /** 仅在一次处理内共享解析结果，不跨 tick 缓存第三方网络。 */
  static Target deferred(java.util.function.Supplier<Target> factory) {
    return new Target() {
      private Target resolved;
      private Target value() {
        if (resolved == null) resolved = factory.get();
        return resolved;
      }
      public ItemStack insert(ItemStack stack, boolean simulate) {
        return value().insert(stack, simulate);
      }
      public boolean unavailable() { return value().unavailable(); }
      public String reason() { return value().reason(); }
      public boolean loops(Sources.Ref source, ServerPlayer player) {
        return value().loops(source, player);
      }
    };
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
    if (mode == 3)
      return ModList.get().isLoaded("beyonddimensions")
          ? beyond(p)
          : failed("未安装超越维度，请切换吸收模式");
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

  private static Target beyond(ServerPlayer player) {
    try {
      return BeyondCompat.target(player);
    } catch (LinkageError error) {
      return failed("超越维度接口不兼容；已停止向该网络存入");
    }
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
