package dev.chestmagnet;

import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

public final class Data {
  public static CompoundTag tag(ItemStack s) {
    return s.getOrCreateTagElement("ChestMagnet");
  }

  public static boolean enabled(ItemStack s) {
    return !tag(s).contains("Enabled") || tag(s).getBoolean("Enabled");
  }

  public static int mode(ItemStack s) {
    return Math.floorMod(tag(s).getInt("Mode"), 3);
  }

  public static String modeName(int m) {
    return new String[] {"物品栏", "RS", "背包"}[Math.floorMod(m, 3)];
  }

  public static int radius(ItemStack s, String axis) {
    return Math.min(
        Config.RADIUS.get(),
        Math.max(0, tag(s).contains(axis) ? tag(s).getInt(axis) : (axis.equals("Y") ? 3 : 8)));
  }

  public static UUID id(ItemStack s) {
    var t = tag(s);
    if (!t.hasUUID("Id")) t.putUUID("Id", UUID.randomUUID());
    return t.getUUID("Id");
  }

  public static ListTag exclusions(ItemStack s) {
    return tag(s).getList("Excluded", Tag.TAG_COMPOUND);
  }

  public static boolean excluded(ItemStack s, String key) {
    for (var t : exclusions(s)) if (((CompoundTag) t).getString("Key").equals(key)) return true;
    return false;
  }

  public static boolean toggle(ItemStack s, String key, String label) {
    ListTag list = exclusions(s);
    for (int i = 0; i < list.size(); i++)
      if (list.getCompound(i).getString("Key").equals(key)) {
        list.remove(i);
        tag(s).put("Excluded", list);
        return false;
      }
    if (list.size() >= Config.EXCLUSIONS.get()) return false;
    var entry = new CompoundTag();
    entry.putString("Key", key);
    entry.putString("Label", label);
    list.add(entry);
    tag(s).put("Excluded", list);
    return true;
  }
}
