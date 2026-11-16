package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;

/** 每玩家的接收历史，不是当前库存，也不是转移事务的一部分。 */
final class RecentItems {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChestMagnet.ID);
  private static final String KEY = "ChestMagnetRecentItems";
  private static final String POOLS = "ChestMagnetRecentPools";
  private static final int LEGACY_LIMIT = 96;
  private static final long MERGE_MILLIS = 60_000;
  static final int LIMIT = 256, PAGE_SIZE = 8;
  static final String[] CATEGORIES = {"全部", "普通物品", "附魔书", "矿物", "饰品", "装备"};
  private static final List<TagKey<Item>> MINERALS = tags("ores", "raw_materials", "ingots", "nuggets", "gems", "dusts");
  private static final List<TagKey<Item>> EQUIPMENT = tags("armors", "tools", "shields");
  private static boolean reportedFailure;

  private static List<TagKey<Item>> tags(String... names) {
    return Arrays.stream(names).map(name -> TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", name))).toList();
  }

  static Destinations.Target track(ServerPlayer player, int mode, Destinations.Target target) {
    return new Destinations.Target() {
      public ItemStack insert(ItemStack stack, boolean simulate) {
        if (simulate || stack.isEmpty()) return target.insert(stack, simulate);
        int offered = stack.getCount();
        CompoundTag display = null;
        try {
          display = describe(player, stack);
        } catch (RuntimeException | LinkageError ex) { failed(ex); }
        var left = target.insert(stack, false);
        int accepted = offered - left.getCount();
        if (display != null && accepted > 0 && accepted <= offered) {
          try {
            append(pools(player), display, mode, accepted, System.currentTimeMillis());
          } catch (RuntimeException | LinkageError ex) { failed(ex); }
        }
        if (accepted > 0 && accepted <= offered) QuestDetection.wake(player);
        return left;
      }
      public boolean unavailable() { return target.unavailable(); }
      public String reason() { return target.reason(); }
      public boolean loops(Sources.Ref source, ServerPlayer p) { return target.loops(source, p); }
    };
  }

  private static void failed(Throwable error) {
    if (reportedFailure) return;
    reportedFailure = true;
    LOG.warn("无法记录磁铁接收历史；不影响物品收取", error);
  }

  private static CompoundTag describe(ServerPlayer player, ItemStack stack) {
    var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    if (id == null) return null;
    var display = new CompoundTag();
    display.putString("Item", id.toString());
    display.putInt("Category", category(player, stack));
    if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME))
      display.putString("Name", clip(stack.getHoverName().getString(), 128));
    if (stack.isDamageableItem()) display.putInt("Damage", stack.getDamageValue());
    var enchantments = new ListTag();
    for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
      var enchant = entry.getKey().unwrapKey().map(net.minecraft.resources.ResourceKey::location).orElse(null);
      if (enchant != null && enchantments.size() < 16) {
        var tag = new CompoundTag();
        tag.putString("id", enchant.toString());
        tag.putInt("lvl", entry.getValue());
        enchantments.add(tag);
      }
    }
    if (!enchantments.isEmpty()) display.put("Enchants", enchantments);
    if (!stack.getComponentsPatch().isEmpty())
      display.putString("Fingerprint", fingerprint(player, stack));

    return display;
  }

  private static String fingerprint(ServerPlayer player, ItemStack stack) {
    // 无需保留完整 NBT；规范化复合标签键顺序后以摘要区分同名变体。
    var digest = com.google.common.hash.Hashing.sha256().newHasher();
    hashTag(digest, StackData.write(player.registryAccess(), stack.copyWithCount(1)), 0);
    return digest.hash().toString();
  }

  private static void hashTag(com.google.common.hash.Hasher hash, Tag tag, int depth) {
    if (depth > 128) throw new IllegalArgumentException("物品显示记录 NBT 嵌套过深");
    hash.putByte(tag.getId());
    if (tag instanceof CompoundTag compound) {
      var keys = compound.getAllKeys().stream().sorted().toList();
      hash.putInt(keys.size());
      for (var key : keys) {
        hash.putInt(key.length()).putUnencodedChars(key);
        hashTag(hash, compound.get(key), depth + 1);
      }
    } else if (tag instanceof ListTag list) {
      hash.putInt(list.size());
      for (Tag child : list) hashTag(hash, child, depth + 1);
    } else {
      String value = tag.getAsString();
      hash.putInt(value.length()).putUnencodedChars(value);
    }
  }

  private static String clip(String text, int max) {
    if (text.length() <= max) return text;
    return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
  }

  private static int category(ServerPlayer player, ItemStack stack) {
    if (stack.getItem() instanceof EnchantedBookItem) return 2;
    if (accessory(player, stack)) return 4;
    var item = stack.getItem();
    if (item instanceof Equipable || item instanceof DiggerItem || item instanceof SwordItem
        || item instanceof ProjectileWeaponItem || item instanceof TridentItem
        || EQUIPMENT.stream().anyMatch(stack::is)) return 5;
    return MINERALS.stream().anyMatch(stack::is) ? 3 : 1;
  }

  private static boolean accessory(ServerPlayer player, ItemStack stack) {
    if (stack.getTags().anyMatch(tag -> tag.location().getNamespace().equals("curios"))) return true;
    if (!ModList.get().isLoaded("curios")) return false;
    try {
      return CuriosCompat.isAccessory(player, stack);
    } catch (RuntimeException | LinkageError error) {
      failed(error);
      return false;
    }
  }

  private static CompoundTag pools(ServerPlayer player) {
    var owner = player.getPersistentData();
    if (owner.contains(POOLS, Tag.TAG_COMPOUND)) return owner.getCompound(POOLS);
    var pools = new CompoundTag();
    var legacy = owner.getList(KEY, Tag.TAG_COMPOUND);
    // 倒序导入；旧格式没有完整变体 NBT，只补判默认物品能证明的饰品。
    for (int i = Math.min(legacy.size(), LEGACY_LIMIT) - 1; i >= 0; i--) {
      var row = legacy.getCompound(i);
      var display = row.getCompound("Display").copy();
      var id = ResourceLocation.tryParse(display.getString("Item"));
      if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
        var item = BuiltInRegistries.ITEM.get(id);
        if (item != null && display.getInt("Category") != 2 && accessory(player, new ItemStack(item)))
          display.putInt("Category", 4);
      }
      int mode = display.getInt("Mode");
      display.remove("Mode");
      append(pools, display, mode, row.getLong("Count"), row.getLong("Time"));
    }
    owner.put(POOLS, pools);
    owner.remove(KEY);
    return pools;
  }

  private static int categoryOf(CompoundTag display) {
    int category = display.getInt("Category");
    return category > 0 && category < CATEGORIES.length ? category : 1;
  }

  private static long plus(long base, long amount) {
    return base + Math.min(amount, Long.MAX_VALUE - base);
  }

  static void append(CompoundTag pools, CompoundTag display, int mode, long amount, long now) {
    if (mode < 0 || mode >= 4 || amount <= 0) return;
    String key = Integer.toString(categoryOf(display));
    var list = pools.getList(key, Tag.TAG_COMPOUND);
    var row = new CompoundTag();
    row.putLong("First", now);
    row.put("Display", display);
    // 同类别最近一分钟才可能匹配，不跨类别查找；连续获取不会无限延长原批次。
    for (int i = 0; i < Math.min(LIMIT, list.size()); i++) {
      var previous = list.getCompound(i);
      long elapsed = now - previous.getLong("Time");
      if (elapsed > MERGE_MILLIS) break;
      long sinceFirst = now - previous.getLong("First");
      if (sinceFirst >= 0 && sinceFirst <= MERGE_MILLIS && display.equals(previous.getCompound("Display"))) {
        row = previous;
        list.remove(i);
        break;
      }
    }
    var amounts = row.getLongArray("Destinations");
    if (amounts.length != 4) amounts = new long[4];
    amounts[mode] = plus(amounts[mode], amount);
    row.putLongArray("Destinations", amounts);
    row.putLong("Count", plus(row.getLong("Count"), amount));
    row.putLong("Time", now);

    // 系统时钟回拨也不破坏每个分类按时间降序的约定。
    int at = 0;
    while (at < Math.min(LIMIT, list.size()) && list.getCompound(at).getLong("Time") > now) at++;
    list.add(at, row);
    while (list.size() > LIMIT) list.remove(list.size() - 1);
    pools.put(key, list);
  }

  static void copy(ServerPlayer from, ServerPlayer to) {
    var data = from.getPersistentData();
    if (data.contains(POOLS)) to.getPersistentData().put(POOLS, data.getCompound(POOLS).copy());
    else if (data.contains(KEY)) to.getPersistentData().put(KEY, data.getList(KEY, Tag.TAG_COMPOUND).copy());
  }

  static CompoundTag page(ServerPlayer player, int category, int requested) {
    return page(pools(player), category, requested);
  }

  static CompoundTag page(CompoundTag pools, int category, int requested) {
    int first = category == 0 ? 1 : category;
    int end = category == 0 ? CATEGORIES.length : first + 1;
    var sources = new ListTag[end - first];
    var cursors = new int[sources.length];
    int total = 0;
    for (int i = first; i < end; i++) {
      var rows = pools.getList(Integer.toString(i), Tag.TAG_COMPOUND);
      sources[i - first] = rows;
      total = Math.min(LIMIT, total + Math.min(LIMIT, rows.size()));
    }
    int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.max(0, Math.min(pages - 1, requested));
    var items = new ListTag();
    if (category != 0) cursors[0] = page * PAGE_SIZE;
    int begin = category == 0 ? 0 : page * PAGE_SIZE;
    for (int i = begin; i < Math.min(total, (page + 1) * PAGE_SIZE); i++) {
      int best = -1;
      long latest = Long.MIN_VALUE;
      for (int j = 0; j < sources.length; j++) {
        if (cursors[j] >= Math.min(LIMIT, sources[j].size())) continue;
        long time = sources[j].getCompound(cursors[j]).getLong("Time");
        if (best < 0 || time > latest) { best = j; latest = time; }
      }
      if (best < 0) break;
      var row = sources[best].getCompound(cursors[best]++);
      if (i >= page * PAGE_SIZE) {
        var clean = row.copy();
        clean.getCompound("Display").remove("Fingerprint");
        clean.remove("First");
        items.add(clean);
      }
    }
    var result = new CompoundTag();
    result.putInt("Page", page);
    result.putInt("Pages", pages);
    result.putInt("Total", total);
    result.put("Rows", items);
    return result;
  }

  private RecentItems() {}
}
