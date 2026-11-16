package dev.chestmagnet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 仅用于安全续交旧版缓存；取消支持的来源不会产生新的待接收物品。 */
final class LootCapture {
  private LootCapture() {}

  static boolean drain(ItemStack m, Destinations.Target target, long deadline, ServerPlayer player) {
    var registries = player.registryAccess();
    var t = Data.tag(m).copy();
    var list = t.getList("PendingLoot", 10);
    int n = 0;
    while (!list.isEmpty() && n++ < Config.STACKS.get() && System.nanoTime() < deadline) {
      var item = StackData.read(registries, list.getCompound(0));
      if (item.isEmpty()) {
        Faults.quarantine(m, "PendingLoot", "无法解析待接收物品，原数据已封存");
        Data.edit(m, data -> data.remove("PendingOrigin"));
        player.displayClientMessage(Component.literal("旧版待接收物品无法迁移，原始数据已封存，可在诊断页查看。"), false);
        return true;
      }
      try {
        if (target.insert(item, true).getCount() == item.getCount()) break;
      } catch (RuntimeException ex) {
        Faults.quarantine(m, "PendingLoot", "模拟接口异常；未调用实际投递，故障来源产物延后封存");
        throw ex;
      }
      Faults.stage("接收已生成产物");
      ItemStack left;
      try {
        left = target.insert(item, false);
      } catch (RuntimeException ex) {
        // 仅本次已尝试交付的栈不确定，其余确定产物仍可续收。
        var batch = new CompoundTag();
        batch.put("Stack", list.remove(0));
        t.put("UncertainBatch", batch);
        t.put("PendingLoot", list);
        Data.replace(m, t);
        Faults.quarantine(m, "UncertainBatch", "接收接口中断，不自动补发");
        Faults.quarantine(m, "PendingLoot", "同故障来源尚未投递产物封存，不影响其他来源");
        throw ex;
      }
      if (left.isEmpty()) list.remove(0);
      else list.set(0, StackData.write(registries, left));
      t.put("PendingLoot", list);
      Data.replace(m, t);
    }
    if (list.isEmpty()) {
      if (t.contains("PendingOrigin") || t.contains("PendingLoot") || n > 0) {
        t.remove("PendingLoot");
        t.remove("PendingOrigin");
        Data.replace(m, t);
      }
    }
    return list.isEmpty();
  }

  static boolean pending(ItemStack m) {
    return !Data.tag(m).getList("PendingLoot", 10).isEmpty();
  }
}
