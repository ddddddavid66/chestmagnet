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
import net.minecraft.core.registries.BuiltInRegistries;

/** 原生匹配为最终判定；候选物品集合只缩小网络索引的查询范围。 */
record QuestMatch(Set<Item> candidates, Predicate<ItemStack> matches) {
  static QuestMatch compile(ItemTask task, net.minecraft.core.HolderLookup.Provider registries) {
    var key = task.getItemStack();
    if (key.isEmpty()) return null;
    if (!ItemMatchingSystem.INSTANCE.isItemFilter(key)) return new QuestMatch(Set.of(key.getItem()), task);
    // 只接受已知过滤系统的候选展开；匹配仍由 FTB 处理，不枚举网络仓库。
    var adapter = ItemMatchingSystem.INSTANCE.getFilterAdapter(key).orElse(null);
    if (adapter == null || !adapter.getClass().getName().startsWith("dev.ftb.mods.ftbquests.")) return null;
    var samples = ItemMatchingSystem.INSTANCE.getAllMatchingStacks(key, registries);
    if (samples.isEmpty() || samples.size() > 2048) return null;
    Set<Item> candidates = new LinkedHashSet<>();
    for (var sample : samples) if (!sample.isEmpty()) candidates.add(sample.getItem());
    return candidates.isEmpty() ? null : new QuestMatch(candidates, task);
  }
}
