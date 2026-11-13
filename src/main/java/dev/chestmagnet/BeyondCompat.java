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
      public Object stockIdentity() { return storage; }

      @Override
      public QuestStock.Indexed stock(java.util.Set<net.minecraft.world.item.Item> candidates) {
        var revision = new java.util.concurrent.atomic.AtomicLong();
        var subscription = storage.subscribeAny(revision, revision::incrementAndGet);
        return new QuestStock.Indexed(new QuestStock.View() {
          public Object identity() { return storage; }
          public Object revision() { return revision.get(); }
          public java.util.List<? extends QuestStock.Row> rows(net.minecraft.world.item.Item item) {
            // 只查询候选物品 ID，不枚举其他物品，也不把 long 数量压成客户端堆叠数量。
            var matches = storage.getFuzzyMatches(new ItemStackKey(new ItemStack(item)));
            return new java.util.AbstractList<QuestStock.Row>() {
              public int size() { return matches.size(); }
              public QuestStock.Row get(int i) {
                var entry = matches.get(i);
                return new QuestStock.Row() {
                  public ItemStack stack() {
                    return entry.key() instanceof ItemStackKey k ? k.getReadOnlyStack() : ItemStack.EMPTY;
                  }
                  public long count() { return entry.amount(); }
                };
              }
            };
          }
        }, candidates, subscription);
      }

      @Override
      public String reason() {
        return "超越维度主网络已满或存储规则拒收，请检查网络";
      }
    };
  }
}
