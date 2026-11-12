package dev.chestmagnet;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 仅在超越维度存在时加载；和原模组手持存入使用同一个主网络。 */
final class BeyondCompat {
  private BeyondCompat() {}

  static Destinations.Target target(ServerPlayer player) {
    var net = DimensionsNet.getPrimaryNetFromPlayer(player);
    if (net == null || net.deleted)
      return Destinations.failed("没有可用的超越维度主网络，请先在原模组中选定主网络");
    var id = player.getUUID();
    if (!net.isOwner(id) && !net.isManager(id) && !net.getPlayers().contains(id))
      return Destinations.failed("没有访问此超越维度主网络的权限");
    var storage = net.getUnifiedStorage();
    return new Destinations.Target() {
      @Override
      public ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        KeyAmount left;
        try {
          left = storage.insert(new ItemStackKey(stack), stack.getCount(), simulate);
        } catch (LinkageError incompatible) {
          // 实际写入后发生链接错误不能擅自重试或恢复完整原栈。
          throw new IllegalStateException("超越维度存入接口不兼容", incompatible);
        }
        long remaining = left.amount();
        if (remaining < 0 || remaining > stack.getCount())
          throw new IllegalStateException("超越维度返回了无效的存入余量");
        return remaining == 0 ? ItemStack.EMPTY : stack.copyWithCount((int) remaining);
      }

      @Override
      public long count(ItemStack key, Predicate<ItemStack> matches, long limit, long deadline) {
        long amount = 0;
        int variants = 0;
        for (var value : storage.getFuzzyMatches(new ItemStackKey(key))) {
          if (amount >= limit || ++variants > 128 || (variants > 4 && System.nanoTime() >= deadline)) break;
          if (value.key() instanceof ItemStackKey item && value.amount() > 0 && matches.test(item.copyStack()))
            amount += Math.min(limit - amount, value.amount());
        }
        return amount;
      }

      @Override
      public String reason() {
        return "超越维度主网络已满或存储规则拒收，请检查网络";
      }
    };
  }
}
