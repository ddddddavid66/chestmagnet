package dev.chestmagnet;

import net.minecraft.server.level.ServerPlayer;

/** 原生人物库存兜底：拾取、手动移动、旧库存均从当前槽位核对，不累计历史。 */
final class PlayerQuestStock implements QuestStock.Reader {
  private final ServerPlayer player;
  private int slot;
  private long count, limit;
  private QuestMatch match;
  PlayerQuestStock(ServerPlayer player) { this.player = player; }
  public boolean step(QuestMatch match, long limit, long deadline) {
    this.match = match;
    this.limit = limit;
    var inventory = player.getInventory();
    int budget = 64;
    while (slot < inventory.getContainerSize() && count < limit && budget-- > 0
        && System.nanoTime() < deadline) {
      var stack = inventory.getItem(slot++);
      if (!stack.isEmpty() && match.candidates().contains(stack.getItem()) && match.matches().test(stack))
        count += Math.min(limit - count, stack.getCount());
    }
    return slot >= inventory.getContainerSize() || count >= limit;
  }
  public long amount() { return count; }
  public boolean valid() {
    // 跨 tick 玩家可能移动物品；提交前重核当前数量，不能拼接新旧槽位凑数。
    long actual = 0;
    var inventory = player.getInventory();
    for (int i = 0; i < inventory.getContainerSize() && actual < limit; i++) {
      var stack = inventory.getItem(i);
      if (!stack.isEmpty() && match.candidates().contains(stack.getItem()) && match.matches().test(stack))
        actual += Math.min(limit - actual, stack.getCount());
    }
    return actual == count;
  }
}
