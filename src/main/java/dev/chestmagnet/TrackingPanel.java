package dev.chestmagnet;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** 只布局当前视口；搜索索引按界面生命周期缓存，不在 render 中遍历注册表。 */
final class TrackingPanel {
  static final int TAB = 5;
  private static final int BODY = 0xfff3ead4, MUTED = 0xffc9bda6, GOLD = 0xffe8bd68;
  private static final String HELP = "右键物品加入追踪列表,再次右键取消追踪";

  private final MagnetMenu menu;
  private List<Tracking.Rule> rules = List.of();
  private List<ItemStack> icons = List.of();
  private List<Entry> catalog, results = List.of();
  private final Set<Tracking.Key> active = new HashSet<>();
  private record Entry(ItemStack stack, String search, Tracking.Rule key) {}
  boolean searching;
  private int searchRows = 2;
  private boolean loaded;
  private int page;
  String query = "", note = "";

  TrackingPanel(MagnetMenu menu) { this.menu = menu; }
  static ItemStack icon(CompoundTag display) { return MagnetScreen.icon(display); }

  void request() { send(0, null, -1, -1); }

  boolean select(ItemStack stack) {
    if (stack.isEmpty()) return false;
    Tracking.Rule rule;
    try { rule = Tracking.selection(stack); }
    catch (RuntimeException | LinkageError error) { rule = null; }
    if (rule == null) note = "请选择带有具体身份的物品，例如已经写入内容的卷轴";
    else send(1, rule, -1, -1);
    return true;
  }

  void history(int sequence, int row) { send(2, null, sequence, row); }

  private void send(int action, Tracking.Rule rule, int sequence, int row) {
    var key = rule == null ? new Tracking.Key("", (byte) 0, "") : rule.key();
    var d = rule == null ? new CompoundTag() : rule.display();
    var encoded = new StringBuilder();
    var enchants = d.getList("Enchants", 10);
    for (int i = 0; i < enchants.size(); i++) {
      var enchant = enchants.getCompound(i);
      String line = enchant.getString("id") + "=" + enchant.getInt("lvl") + "\n";
      if (encoded.length() + line.length() <= 512) encoded.append(line);
    }
    if (key.item().length() > 256) { note = "此物品的注册名称太长，无法追踪"; return; }
    Net.trackingEdit(new Net.TrackingEdit(menu.identity, menu.containerId, action, sequence, row,
        key.item(), key.kind(), key.variant(), d.getString("Name"), d.getString("Variant"), encoded.toString()));
  }

  void receive(Net.TrackingList message) {
    if (message.data() == null) return;
    var rows = message.data().getList("Rows", 10);
    var next = new ArrayList<Tracking.Rule>();
    active.clear();
    for (int i = 0; i < Math.min(rows.size(), Tracking.LIMIT); i++) {
      var rule = Tracking.load(rows.getCompound(i));
      next.add(rule);
      active.add(rule.key());
    }
    rules = List.copyOf(next);
    icons = rules.stream().map(rule -> icon(rule.display())).toList();
    note = message.note();
    loaded = true;
  }

  void search(String text) {
    query = text;
    if (catalog == null) {
      var entries = new ArrayList<Entry>();
      ForgeRegistries.ITEMS.getValues().forEach(item -> {
        var id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null || item == net.minecraft.world.item.Items.AIR) return;
        var stack = new ItemStack(item);
        if (!stack.isEmpty()) entries.add(entry(stack, id.toString(), null));
      });
      entries.sort(Comparator.comparing(Entry::search));
      catalog = entries;
    }
    var tokens = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
    var found = new ArrayList<Entry>();
    var player = Minecraft.getInstance().player;
    // 没有 JEI 样例时，可以从随身栏选择已有的精确变体。
    if (player != null) {
      var seen = new HashSet<Tracking.Key>();
      var slots = new ArrayList<>(player.getInventory().items);
      slots.add(player.getOffhandItem());
      for (var stack : slots) {
        if (stack.isEmpty() || !stack.hasTag()) continue;
        Tracking.Rule rule;
        try { rule = Tracking.selection(stack); }
        catch (RuntimeException | LinkageError ignored) { continue; }
        if (rule == null || rule.key().kind() == Tracking.ITEM || !seen.add(rule.key())) continue;
        var candidate = entry(stack.copyWithCount(1), rule.key().item() + " " + rule.display().getString("Variant"), rule);
        if (contains(candidate, tokens)) found.add(candidate);
      }
    }
    for (var candidate : catalog) if (contains(candidate, tokens)) found.add(candidate);
    results = found;
    page = 0;
  }

  private static boolean contains(Entry candidate, String[] tokens) {
    for (var token : tokens) if (!candidate.search().contains(token)) return false;
    return true;
  }

  private static Entry entry(ItemStack stack, String id, Tracking.Rule rule) {
    String name;
    try { name = stack.getHoverName().getString(); }
    catch (RuntimeException | LinkageError ignored) { name = id; }
    return new Entry(stack, (name + " " + id).toLowerCase(Locale.ROOT), rule);
  }

  private static int cols(int width) { return Math.max(1, (width - 26) / 20); }
  void layout(int height) { searchRows = height < 165 ? 1 : 2; }
  int paginationOffset() { return 53 + 20 * searchRows; }
  private int pageSize(int width) { return cols(width) * searchRows; }
  private int pages(int width) { return Math.max(1, (results.size() + pageSize(width) - 1) / pageSize(width)); }
  void page(int delta, int width) { page = Math.max(0, Math.min(pages(width) - 1, page + delta)); }
  private int listTop() { return searching ? paginationOffset() + 28 : 59; }
  int contentHeight() { return listTop() + 14 + Math.max(1, rules.size()) * 26 + 5; }

  void draw(GuiGraphics g, Font font, int w, int top, int bottom, int scroll, int mx, int my) {
    int y = top - scroll;
    g.drawWordWrap(font, Component.literal(HELP), 13, y + 2, w - 26, MUTED);
    if (searching) {
      page(0, w);
      int cells = pageSize(w);
      for (int i = 0; i < cells && i + cells * page < results.size(); i++) {
        var entry = results.get(i + cells * page);
        int x = 13 + i % cols(w) * 20, yy = y + 50 + i / cols(w) * 20;
        boolean selected = tracked(entry);
        g.fill(x, yy, x + 18, yy + 18, selected ? 0xff79623d : 0xff343942);
        g.renderFakeItem(entry.stack(), x + 1, yy + 1);
      }
      g.drawString(font, results.isEmpty() ? "没有匹配物品" : (page + 1) + " / " + pages(w),
          66, y + paginationOffset() + 6, MUTED, false);
    }
    int list = y + listTop();
    g.fill(12, list - 4, w - 12, list - 3, 0xff776345);
    g.drawString(font, "正在追踪", 13, list + 1, GOLD, false);
    var count = rules.size() + " / " + Tracking.LIMIT;
    g.drawString(font, count, w - 13 - font.width(count), list + 1, MUTED, false);
    if (rules.isEmpty())
      g.drawString(font, loaded ? "还没有追踪物品" : "正在同步追踪列表…", 13, list + 18, BODY, false);
    for (int i = 0; i < rules.size(); i++) {
      int yy = list + 14 + i * 26;
      if (yy + 24 < top || yy >= bottom) continue;
      var rule = rules.get(i);
      var icon = icons.get(i);
      boolean hover = mx >= 12 && mx < w - 12 && my >= yy && my < yy + 24;
      if (hover || i % 2 == 0) g.fill(12, yy, w - 12, yy + 24, hover ? 0xff363a43 : 0xff292d34);
      g.renderFakeItem(icon, 15, yy + 4);
      String name = MagnetScreen.itemName(rule.display(), icon);
      g.drawString(font, font.plainSubstrByWidth(name, w - 49), 36, yy + 2, BODY, false);
      g.drawString(font, mode(rule), 36, yy + 13, MUTED, false);
    }
  }

  private boolean tracked(Entry entry) {
    if (entry.key() != null) return active.contains(entry.key().key());
    var id = ForgeRegistries.ITEMS.getKey(entry.stack().getItem());
    return id != null && active.contains(new Tracking.Key(id.toString(), Tracking.ITEM, ""));
  }

  private static String mode(Tracking.Rule rule) {
    return switch (rule.key().kind()) {
      case Tracking.SCROLL -> "卷轴身份";
      case Tracking.TAG -> "精确变体";
      default -> "所有变体";
    };
  }

  private Entry hitCatalog(int w, int x, int offset) {
    if (!searching || x < 13 || offset < 50 || offset >= 50 + 20 * searchRows) return null;
    int cell = (x - 13) / 20;
    if (cell >= cols(w) || (x - 13) % 20 >= 18 || (offset - 50) % 20 >= 18) return null;
    int i = page * pageSize(w) + cell + (offset - 50) / 20 * cols(w);
    return i < results.size() ? results.get(i) : null;
  }

  private Tracking.Rule hitRules(int w, int x, int offset) {
    int y = offset - listTop() - 14;
    if (x < 12 || x >= w - 12 || y < 0 || y % 26 >= 24 || y / 26 >= rules.size()) return null;
    return rules.get(y / 26);
  }

  boolean click(int w, int x, int offset) {
    var selected = hitCatalog(w, x, offset);
    if (selected != null) return select(selected.stack());
    var existing = hitRules(w, x, offset);
    if (existing == null) return false;
    send(3, existing, -1, -1);
    return true;
  }

  void tooltip(GuiGraphics g, Font font, int w, int x, int offset, int mx, int my) {
    var sample = hitCatalog(w, x, offset);
    if (sample != null) { g.renderTooltip(font, sample.stack(), mx, my); return; }
    var existing = hitRules(w, x, offset);
    if (existing == null) return;
    var icon = icon(existing.display());
    var lines = new ArrayList<>(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(Minecraft.getInstance(), icon));
    lines.add(Component.literal(mode(existing) + " · 右键取消追踪"));
    g.renderTooltip(font, lines, icon.getTooltipImage(), icon, mx, my);
  }
}
