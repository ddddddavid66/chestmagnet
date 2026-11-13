package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** 每玩家的接收历史，不是当前库存，也不是转移事务的一部分。 */
final class RecentItems {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChestMagnet.ID);
  private static final String KEY = "ChestMagnetRecentItems";
  static final int LIMIT = 96, PAGE_SIZE = 8;
  static final String[] CATEGORIES = {"全部", "普通物品", "附魔书", "矿物", "饰品", "装备"};
  private static final List<TagKey<Item>> MINERALS = tags("ores", "raw_materials", "ingots", "nuggets", "gems", "dusts");
  private static final List<TagKey<Item>> EQUIPMENT = tags("armors", "tools", "shields");
  private static boolean reportedFailure;

  private static List<TagKey<Item>> tags(String... names) {
    return Arrays.stream(names).map(name -> TagKey.create(Registries.ITEM, new ResourceLocation("forge", name))).toList();
  }

  static Destinations.Target track(ServerPlayer player, int mode, Destinations.Target target) {
    return new Destinations.Target() {
      public ItemStack insert(ItemStack stack, boolean simulate) {
        if (simulate || stack.isEmpty()) return target.insert(stack, simulate);
        int offered = stack.getCount();
        CompoundTag display = null;
        try {
          display = describe(player, stack, mode);
        } catch (RuntimeException | LinkageError ex) { failed(ex); }
        var left = target.insert(stack, false);
        int accepted = offered - left.getCount();
        if (display != null && accepted > 0 && accepted <= offered) {
          try {
            append(player.getPersistentData(), display, accepted, System.currentTimeMillis());
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

  private static CompoundTag describe(ServerPlayer player, ItemStack stack, int mode) {
    var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
    if (id == null) return null;
    var display = new CompoundTag();
    display.putString("Item", id.toString());
    display.putInt("Mode", mode);
    display.putInt("Category", category(player, stack));
    if (stack.hasCustomHoverName())
      display.putString("Name", clip(stack.getHoverName().getString(), 128));
    if (stack.isDamageableItem()) display.putInt("Damage", stack.getDamageValue());
    var enchantments = new ListTag();
    for (var entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
      var enchant = ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey());
      if (enchant != null && enchantments.size() < 16) {
        var tag = new CompoundTag();
        tag.putString("id", enchant.toString());
        tag.putInt("lvl", entry.getValue());
        enchantments.add(tag);
      }
    }
    if (!enchantments.isEmpty()) display.put("Enchants", enchantments);
    if (id.getNamespace().equals("tetra") && id.getPath().equals("scroll_rolled")) {
      var scrolls = ScrollIdentity.read(stack);
      if (!scrolls.isEmpty()) display.putString("Variant", clip(scrolls.get(0).key(), 96));
    }
    // 仅本地合并键；真实标签和能力不保存、不发送。
    if (stack.hasTag()) display.putString("Fingerprint", fingerprint(stack));

    return display;
  }

  private static String fingerprint(ItemStack stack) {
    // 无需保留完整 NBT；规范化复合标签键顺序后以摘要区分同名变体。
    var digest = com.google.common.hash.Hashing.sha256().newHasher();
    hashTag(digest, stack.getTag(), 0);
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
    if (ModList.get().isLoaded("curios") && CuriosCompat.isAccessory(player, stack)) return 4;
    var item = stack.getItem();
    if (item instanceof Equipable || item instanceof DiggerItem || item instanceof SwordItem
        || item instanceof ProjectileWeaponItem || item instanceof TridentItem
        || EQUIPMENT.stream().anyMatch(stack::is)) return 5;
    return MINERALS.stream().anyMatch(stack::is) ? 3 : 1;
  }

  static void append(CompoundTag owner, CompoundTag display, int amount, long now) {
    var list = owner.getList(KEY, Tag.TAG_COMPOUND);
    long count = amount;
    if (!list.isEmpty()) {
      var last = list.getCompound(0);
      long elapsed = now - last.getLong("Time");
      if (elapsed >= 0 && elapsed <= 5000 && display.equals(last.getCompound("Display"))) {
        count += Math.min(Long.MAX_VALUE - amount, last.getLong("Count"));
        list.remove(0);
      }
    }
    var row = new CompoundTag();
    row.put("Display", display);
    row.putLong("Count", count);
    row.putLong("Time", now);
    list.add(0, row);
    while (list.size() > LIMIT) list.remove(list.size() - 1);
    owner.put(KEY, list);
  }

  static void copy(ServerPlayer from, ServerPlayer to) {
    var rows = from.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
    if (!rows.isEmpty()) to.getPersistentData().put(KEY, rows.copy());
  }

  static CompoundTag page(CompoundTag owner, int category, int requested) {
    var source = owner.getList(KEY, Tag.TAG_COMPOUND);
    var rows = new ArrayList<CompoundTag>();
    for (int i = 0; i < Math.min(LIMIT, source.size()); i++) {
      var row = source.getCompound(i);
      if (category == 0 || row.getCompound("Display").getInt("Category") == category) rows.add(row);
    }
    int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.max(0, Math.min(pages - 1, requested));
    var items = new ListTag();
    for (int i = page * PAGE_SIZE; i < Math.min(rows.size(), (page + 1) * PAGE_SIZE); i++) {
      var row = rows.get(i).copy();
      row.getCompound("Display").remove("Fingerprint");
      items.add(row);
    }
    var result = new CompoundTag();
    result.putInt("Page", page);
    result.putInt("Pages", pages);
    result.putInt("Total", rows.size());
    result.put("Rows", items);
    return result;
  }

  private RecentItems() {}
}
