package dev.chestmagnet;

import com.google.common.hash.Hashing;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** 追踪是每玩家的规则，而不是历史记录或库存快照。 */
final class Tracking {
  static final int LIMIT = 64;
  static final byte ITEM = 0, TAG = 1, SCROLL = 2;
  private static final String DATA = "ChestMagnetTracking";
  private static final String TETRA = "tetra:scroll_rolled";
  private static final Map<UUID, Map<String, List<Rule>>> CACHE = new HashMap<>();

  record Key(String item, byte kind, String variant) {
    boolean valid() {
      var id = ResourceLocation.tryParse(item);
      return id != null && item.length() <= 256 && item.equals(id.toString()) && ForgeRegistries.ITEMS.containsKey(id) && !item.equals("minecraft:air")
          && switch (kind) {
            case ITEM -> variant.isEmpty() && !item.equals(TETRA);
            case TAG -> hex(variant);
            case SCROLL -> item.equals(TETRA) && hex(variant);
            default -> false;
          };
    }
    private static boolean hex(String s) { return s.length() == 64 && s.matches("[0-9a-f]{64}"); }
    String token() { return item + "/" + kind + "/" + variant; }
  }

  record Rule(Key key, CompoundTag display) {
    CompoundTag save() {
      var n = new CompoundTag();
      n.putString("Item", key.item());
      n.putByte("Kind", key.kind());
      n.putString("Variant", key.variant());
      n.put("Display", display.copy());
      return n;
    }
  }

  static Rule load(CompoundTag n) {
    return new Rule(new Key(n.getString("Item"), n.getByte("Kind"), n.getString("Variant")), n.getCompound("Display"));
  }

  static Rule selection(ItemStack stack) {
    var display = RecentItems.describe(null, stack);
    return display == null ? null : fromHistory(display);
  }

  static Rule fromHistory(CompoundTag display) {
    String item = display.getString("Item");
    String tag = display.getBoolean("PlainSelection") ? "" : display.getString("Fingerprint");
    String scroll = display.getString("ScrollKey");
    if (item.equals(TETRA) && scroll.isEmpty() && (tag.isEmpty() || display.getString("Variant").isEmpty()))
      return null;
    var key = !scroll.isEmpty() ? new Key(item, SCROLL, scroll)
        : !tag.isEmpty() ? new Key(item, TAG, tag) : new Key(item, ITEM, "");
    var safe = RecentItems.publicDisplay(display);
    return key.valid() ? new Rule(key, safe) : null;
  }

  static String scrollKey(ItemStack stack) {
    var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
    if (id == null || !TETRA.equals(id.toString())) return "";
    var rows = ScrollIdentity.read(stack);
    if (rows.isEmpty()) return "";
    var encoded = new ArrayList<String>(rows.size());
    for (var row : rows) {
      var value = new StringBuilder();
      field(value, row.key());
      fields(value, row.effects());
      fields(value, row.schematics());
      encoded.add(value.toString());
    }
    var value = new StringBuilder();
    fields(value, encoded);
    return Hashing.sha256().hashUnencodedChars(value).toString();
  }

  private static void fields(StringBuilder into, Collection<String> values) {
    into.append(values.size()).append(':');
    values.stream().sorted().forEach(v -> field(into, v));
  }

  private static void field(StringBuilder into, String value) {
    into.append(value.length()).append(':').append(value);
  }

  static List<Rule> rules(ServerPlayer player) {
    var persisted = player.getPersistentData().getList(DATA, Tag.TAG_COMPOUND);
    var rules = new ArrayList<Rule>();
    for (int i = 0; i < Math.min(persisted.size(), LIMIT); i++) {
      var rule = load(persisted.getCompound(i));
      if (rule.key.valid()) rules.add(rule);
    }
    return rules;
  }

  static void copy(ServerPlayer from, ServerPlayer to) {
    if (from.getPersistentData().contains(DATA, Tag.TAG_LIST))
      to.getPersistentData().put(DATA, from.getPersistentData().getList(DATA, Tag.TAG_COMPOUND).copy());
    remove(to.getUUID());
  }

  static void remove(UUID player) { CACHE.remove(player); }

  static boolean toggle(ServerPlayer player, Rule rule) {
    if (rule == null || !rule.key.valid()) return false;
    var rules = rules(player);
    boolean removed = rules.removeIf(existing -> existing.key.equals(rule.key));
    if (!removed) {
      if (rules.size() >= LIMIT) return false;
      rules.add(rule);
    }
    var next = new ListTag();
    rules.forEach(r -> next.add(r.save()));
    player.getPersistentData().put(DATA, next);
    remove(player.getUUID());
    return true;
  }

  static Rule match(ServerPlayer player, ItemStack input) {
    var id = ForgeRegistries.ITEMS.getKey(input.getItem());
    if (id == null) return null;
    var indexed = CACHE.computeIfAbsent(player.getUUID(), ignored -> {
      var map = new HashMap<String, List<Rule>>();
      for (var rule : rules(player)) map.computeIfAbsent(rule.key.item(), k -> new ArrayList<>()).add(rule);
      return map;
    });
    var matching = indexed.get(id.toString());
    if (matching == null) return null;
    String scroll = null, tag = null;
    Rule generic = null;
    for (var rule : matching) {
      if (rule.key.kind() == ITEM) generic = rule;
      else if (rule.key.kind() == SCROLL) {
        if (scroll == null) scroll = scrollKey(input);
        if (rule.key.variant().equals(scroll)) return rule;
      } else {
        if (tag == null) tag = input.hasTag() ? RecentItems.fingerprint(input) : "";
        if (rule.key.variant().equals(tag)) return rule;
      }
    }
    return generic;
  }

  private Tracking() {}
}
