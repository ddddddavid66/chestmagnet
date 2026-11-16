package dev.chestmagnet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 普通模式与结构模式共享的提取、接收和恢复缓存协议。 */
public final class Transfer {
  static int move(
      IItemHandler inv, int slot, int accepted, Destinations.Target target, ItemStack magnet, HolderLookup.Provider registries) {
    Faults.stage("实际提取槽位 " + slot);
    var evidence = new CompoundTag();
    evidence.putInt("Slot", slot);
    evidence.put("Before", StackData.write(registries, inv.getStackInSlot(slot)));
    Data.edit(magnet, data -> data.put("OperationEvidence", evidence));
    var actual = inv.extractItem(slot, accepted, false);
    if (actual.isEmpty()) {
      Data.edit(magnet, data -> data.remove("OperationEvidence"));
      return 0;
    }
    var t = Data.tag(magnet).copy();
    t.put("Recovery", StackData.write(registries, actual));
    t.putBoolean("RecoveryUncertain", true);
    Data.replace(magnet, t);
    Faults.stage("实际接收槽位 " + slot);
    var left = target.insert(actual, false);
    t.remove("RecoveryUncertain");
    int count = actual.getCount() - left.getCount();
    if (!left.isEmpty()) {
      t.put("Recovery", StackData.write(registries, left));
      t.putBoolean("RecoveryUncertain", true);
      Data.replace(magnet, t);
      left = inv.insertItem(slot, left, false);
      t.remove("RecoveryUncertain");
    }
    if (left.isEmpty()) t.remove("Recovery");
    else {
      t.put("Recovery", StackData.write(registries, left));
      t.putBoolean("Enabled", false);
    }
    t.remove("OperationEvidence");
    Data.replace(magnet, t);
    return count;
  }
}
