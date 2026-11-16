package dev.chestmagnet;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;

public final class Data {
  public static CompoundTag tag(ItemStack s) {
    // 只读视图用于热路径；写入必须经过 edit/replace，不能原地修改组件。
    return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe().getCompound("ChestMagnet");
  }

  public static void edit(ItemStack stack, Consumer<CompoundTag> change) {
    var data = tag(stack).copy();
    change.accept(data);
    replace(stack, data);
  }

  static void replace(ItemStack stack, CompoundTag data) {
    CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put("ChestMagnet", data.copy()));
  }

  public static boolean enabled(ItemStack s) {
    return !tag(s).contains("Enabled") || tag(s).getBoolean("Enabled");
  }

  public static int mode(ItemStack s) {
    return normalizeMode(tag(s).getInt("Mode"));
  }

  /** 旧磁铁不自动确认整座结构，必须由玩家明确开启。 */
  public static boolean autoStructure(ItemStack s) {
    return tag(s).getBoolean("AutoStructure");
  }

  public static int normalizeMode(int value) {
    return Math.floorMod(value, 4);
  }

  public static boolean questDetection(CompoundTag data) {
    return !data.contains("QuestDetection") || data.getBoolean("QuestDetection");
  }

  public static String modeName(int m) {
    return new String[] {"物品栏", "RS", "背包", "超越维度"}[normalizeMode(m)];
  }

  public static int radius(ItemStack s, String axis) {
    return Math.min(
        Config.RADIUS.get(),
        Math.max(0, tag(s).contains(axis) ? tag(s).getInt(axis) : (axis.equals("Y") ? 3 : 8)));
  }

  public static UUID id(ItemStack s) {
    var t = tag(s);
    if (t.hasUUID("Id")) return t.getUUID("Id");
    var id = UUID.randomUUID();
    edit(s, data -> data.putUUID("Id", id));
    return id;
  }

  public static ListTag exclusions(ItemStack s) {
    return tag(s).getList("Excluded", Tag.TAG_COMPOUND);
  }

  public static boolean excluded(ItemStack s, String key) {
    for (var t : exclusions(s)) if (((CompoundTag) t).getString("Key").equals(key)) return true;
    return false;
  }

  public static boolean toggle(ItemStack s, String key, String label) {
    ListTag list = exclusions(s).copy();
    for (int i = 0; i < list.size(); i++)
      if (list.getCompound(i).getString("Key").equals(key)) {
        list.remove(i);
        edit(s, t -> t.put("Excluded", list));
        return false;
      }
    if (list.size() >= Config.EXCLUSIONS.get()) return false;
    var entry = new CompoundTag();
    entry.putString("Key", key);
    entry.putString("Label", label);
    list.add(entry);
    edit(s, t -> t.put("Excluded", list));
    return true;
  }
}
