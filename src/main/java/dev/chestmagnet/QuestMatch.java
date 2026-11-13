package dev.chestmagnet;

import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** 原生匹配为最终判定；候选物品集合只缩小网络索引的查询范围。 */
record QuestMatch(Set<Item> candidates, Predicate<ItemStack> matches) {
  static QuestMatch compile(ItemTask task) {
    var key = task.getItemStack();
    Set<Item> ids = new LinkedHashSet<>();
    if (!expand(key, ids, 0, new int[]{0}) || ids.isEmpty()) return null;
    if (!id(key, "tetra", "scroll_rolled") || !key.hasTag()) return new QuestMatch(ids, task);
    var config = new CompoundTag();
    task.writeData(config);
    if (config.contains("match_nbt") || !key.getTag().contains("BlockEntityTag"))
      return new QuestMatch(ids, task);
    var required = ScrollIdentity.read(key);
    return new QuestMatch(ids, actual -> task.test(actual) && ScrollIdentity.contains(required, ScrollIdentity.read(actual)));
  }

  private static boolean expand(ItemStack key, Set<Item> ids, int depth, int[] nodes) {
    if (depth > 12 || ++nodes[0] > 256 || key.isEmpty()) return false;
    if (!ItemMatchingSystem.INSTANCE.isItemFilter(key)) {
      ids.add(key.getItem());
      return true;
    }
    var tag = key.getTag();
    if (tag == null) return false;
    if (id(key, "itemfilters", "tag")) {
      var name = ResourceLocation.tryParse(tag.getString("value"));
      var tags = ForgeRegistries.ITEMS.tags();
      if (name == null || tags == null || name.getPath().isEmpty()) return false;
      var members = tags.getTag(TagKey.create(Registries.ITEM, name));
      if (members.size() > 2048) return false;
      for (var member : members) {
        ids.add(member);
        if (ids.size() > 2048) return false;
      }
      return true;
    }
    if (!id(key, "itemfilters", "or")) return false;
    var children = tag.getList("items", Tag.TAG_COMPOUND);
    if (children.isEmpty() || children.size() > 256) return false;
    for (int i = 0; i < children.size(); i++) {
      // OR 可能包含其他过滤器；无法覆盖的分支不拿不完整候选集合冒充完整匹配。
      var child = ItemStack.of(children.getCompound(i));
      if (!child.isEmpty() && !expand(child, ids, depth + 1, nodes)) return false;
    }
    return true;
  }

  private static boolean id(ItemStack key, String namespace, String path) {
    var id = ForgeRegistries.ITEMS.getKey(key.getItem());
    return id != null && id.getNamespace().equals(namespace) && id.getPath().equals(path);
  }

}
