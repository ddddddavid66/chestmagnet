package dev.chestmagnet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/** 普通模式与结构模式共享的提取、接收和恢复缓存协议。 */
public final class Transfer {
  static int move(
      IItemHandler inv, int slot, int accepted, Destinations.Target target, ItemStack magnet) {
    var actual = inv.extractItem(slot, accepted, false);
    if (actual.isEmpty()) return 0;
    var t = Data.tag(magnet);
    t.put("Recovery", actual.save(new CompoundTag()));
    t.putBoolean("RecoveryUncertain", true);
    var left = target.insert(actual, false);
    t.remove("RecoveryUncertain");
    int count = actual.getCount() - left.getCount();
    if (!left.isEmpty()) {
      t.put("Recovery", left.save(new CompoundTag()));
      t.putBoolean("RecoveryUncertain", true);
      left = inv.insertItem(slot, left, false);
      t.remove("RecoveryUncertain");
    }
    if (left.isEmpty()) t.remove("Recovery");
    else {
      t.put("Recovery", left.save(new CompoundTag()));
      t.putBoolean("Enabled", false);
    }
    return count;
  }
}
