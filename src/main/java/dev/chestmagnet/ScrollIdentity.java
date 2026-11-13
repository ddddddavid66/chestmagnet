package dev.chestmagnet;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

final class ScrollIdentity {
  record Row(String key, Set<String> effects, Set<String> schematics) {}

  static List<Row> read(ItemStack stack) {
    var block = stack.getTagElement("BlockEntityTag");
    if (block == null) return List.of();
    var data = block.getList("data", Tag.TAG_COMPOUND);
    if (data.size() > 64) return List.of();
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < data.size(); i++) {
      var row = data.getCompound(i);
      String key = row.getString("key");
      var effects = strings(row, "effects");
      var schematics = strings(row, "schematics");
      if (key.isBlank() || effects == null || schematics == null) return List.of();
      rows.add(new Row(key, effects, schematics));
    }
    return rows;
  }

  private static Set<String> strings(CompoundTag tag, String key) {
    if (!tag.contains(key)) return Set.of();
    if (!(tag.get(key) instanceof ListTag list) || list.size() > 128) return null;
    Set<String> values = new HashSet<>();
    for (Tag value : list) {
      if (!(value instanceof StringTag) || value.getAsString().isBlank()) return null;
      values.add(value.getAsString());
    }
    return values;
  }

  static boolean contains(List<Row> required, List<Row> actual) {
    if (required.isEmpty() || actual.size() < required.size()) return false;
    var remaining = new ArrayList<>(actual);
    for (var row : required) if (!remaining.remove(row)) return false;
    return true;
  }
}
