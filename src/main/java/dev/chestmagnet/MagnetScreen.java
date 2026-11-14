package dev.chestmagnet;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** 设置与范围、诊断分离，所有控件按内容行布局，底部不参与内容流。 */
public class MagnetScreen extends AbstractContainerScreen<MagnetMenu> {
  private CompoundTag data = new CompoundTag();
  private String status = "同步中…";
  private int max = 64, tab, page, scroll;
  private boolean confirm, dirty;
  private int category, historyPage, historyRequest = -1, historySequence, historyWait;
  private CompoundTag history;
  private boolean choosingCategory;
  private int categoryCursor, categoryOffset;
  private Button categoryButton;
  private java.util.List<RecentRow> recentRows = java.util.List.of();

  private record RecentRow(net.minecraft.world.item.ItemStack icon, String name, String amount,
      String detail, java.util.List<Component> tooltip) {}
  private int contentHeight;
  private final EditBox[] axes = new EditBox[3];
  private final String[] draft = new String[3];
  private int contentTop, contentBottom;
  private static final String[] TABS = {"常规", "范围", "最近获取物品", "排除", "诊断"};

  public MagnetScreen(MagnetMenu menu, Inventory inv, Component title) {
    super(menu, inv, title);
    imageWidth = 350;
    imageHeight = 280;
  }

  private int radius(String axis) {
    return data.contains(axis) ? data.getInt(axis) : (axis.equals("Y") ? 3 : 8);
  }

  private boolean enabled() {
    return !data.contains("Enabled") || data.getBoolean("Enabled");
  }

  public void update(Net.Snapshot s) {
    if (s.data() == null
        || !s.data().hasUUID("Id")
        || !s.data().getUUID("Id").equals(menu.identity)) return;
    data = s.data();
    status = s.status();
    max = s.maxRadius();
    // 不销毁正在输入的范围文本；其他页随服务端状态刷新。
    if (tab != 2 && !(tab == 1 && dirty) && minecraft != null) init();
  }

  private void send(int action, int value) {
    Net.request(action, value, menu.identity);
  }

  private Button fixed(int x, int y, int w, String label, Runnable action) {
    var b =
        Button.builder(Component.literal(label), v -> action.run())
            .bounds(leftPos + x, topPos + y, Math.max(20, w), 20)
            .build();
    addRenderableWidget(b);
    return b;
  }

  private Button row(int y, String label, Runnable action) {
    return row(12, y, imageWidth - 24, label, action);
  }

  private Button row(int x, int y, int w, String label, Runnable action) {
    int actual = contentTop + y - scroll;
    if (actual < contentTop || actual + 20 > contentBottom) return null;
    return fixed(x, actual, w, label, action);
  }

  private void text(GuiGraphics g, String s, int y, int color) {
    int actual = contentTop + y - scroll;
    if (actual >= contentTop && actual + 10 <= contentBottom)
      g.drawString(font, font.plainSubstrByWidth(s, imageWidth - 28), 14, actual, color, false);
  }

  @Override
  protected void init() {
    imageWidth = Math.min(390, width - 12);
    imageHeight = Math.min(300, height - 8);
    super.init();
    clearWidgets();
    if (ClientEvents.latest != null
        && ClientEvents.latest.data() != null
        && ClientEvents.latest.data().hasUUID("Id")
        && ClientEvents.latest.data().getUUID("Id").equals(menu.identity)) {
      data = ClientEvents.latest.data();
      status = ClientEvents.latest.status();
      max = ClientEvents.latest.maxRadius();
    }
    int available = imageWidth - 24, required = 16;
    for (var label : TABS) required += font.width(label) + 12;
    int columns = available >= required ? 5 : available >= 190 ? 3 : 2;
    int rows = (TABS.length + columns - 1) / columns;
    int nx = 12;
    int tw = (available - (columns - 1) * 4) / columns;
    for (int i = 0; i < 5; i++) {
      final int t = i;
      if (i % columns == 0) nx = 12;
      int navWidth = columns == 5
          ? font.width(TABS[i]) + 12 + (available - required) / 5
          : Math.max(tw, font.width(TABS[i]) + 10);
      if (columns == 3 && i == 2) navWidth = available + 12 - nx;
      var b =
          fixed(
              nx,
              27 + (i / columns) * 24,
              navWidth,
              TABS[i],
              () -> {
                if (tab != 2 && t == 2) category = 0;
                choosingCategory = false;
                tab = t;
                scroll = 0;
                confirm = false;
                dirty = false;
                if (tab == 2) loadRecent(0);
                init();
              });
      nx += navWidth + 4;
      if (tab == i)
        b.setMessage(Component.literal(TABS[i]).withStyle(net.minecraft.ChatFormatting.GOLD));
    }
    if (!dirty) for (int i = 0; i < 3; i++) draft[i] = "" + radius(new String[] {"X", "Y", "Z"}[i]);
    contentTop = 27 + rows * 24 + 4;
    contentBottom = imageHeight - 42;
    fixed(imageWidth - 68, imageHeight - 26, 56, "完成", this::onClose);
    contentHeight = switch (tab) {
      case 0 -> 202;
      case 1 -> 278;
      case 2 -> 44 + (history == null ? 1 : history.getList("Rows", 10).size()) * 27;
      default -> 156;
    };
    scroll = Math.min(scroll, Math.max(0, contentHeight - (contentBottom - contentTop)));
    if (tab == 0) {
      row(0, enabled() ? "磁铁吸附：已开启" : "磁铁吸附：已关闭", () -> send(2, 0));
      row(24, "吸收模式：" + Data.modeName(data.getInt("Mode")), () -> send(1, data.getInt("Mode") + 1));
      row(48, "开启自动吸收结构的宝箱：" + (data.getBoolean("AutoStructure") ? "是" : "否"), () -> send(15, 0));
      row(72, "让任务可以检测吸收的物品：" + (Data.questDetection(data) ? "是" : "否"), () -> send(18, 0));
      var task = data.getCompound("StructureTask");
      var cont = row(12, 178, (imageWidth - 28) / 2, "继续任务", () -> send(16, 0));
      if (cont != null) cont.active = task.getBoolean("CanContinue");
      var stop =
          row(16 + (imageWidth - 28) / 2, 178, (imageWidth - 28) / 2, "停止任务", () -> send(17, 0));
      if (stop != null) stop.active = task.getBoolean("Running") || task.getBoolean("Paused");
    } else if (tab == 1) {
      for (int i = 0; i < 3; i++) {
        final int a = i;
        int yy = 24 + i * 26;
        row(imageWidth - 128, yy, 24, "−", () -> adjust(a, -1));
        int actual = contentTop + yy - scroll;
        if (actual >= contentTop && actual + 20 <= contentBottom) {
          var field =
              new EditBox(
                  font,
                  leftPos + imageWidth - 104,
                  topPos + actual,
                  54,
                  20,
                  Component.literal("半径"));
          field.setMaxLength(3);
          field.setFilter(v -> v.matches("[0-9]{0,3}"));
          field.setValue(draft[i]);
          field.setResponder(
              v -> {
                draft[a] = v;
                dirty = true;
              });
          axes[i] = field;
          addRenderableWidget(field);
        }
        row(imageWidth - 46, yy, 34, "+", () -> adjust(a, 1));
      }
      row(
          108,
          "应用范围",
          () -> {
            int[] values = new int[3];
            try {
              for (int i = 0; i < 3; i++) values[i] = Math.min(max, Integer.parseInt(draft[i]));
            } catch (Exception ex) {
              status = "请输入有效的非负整数";
              return;
            }
            for (int i = 0; i < 3; i++) send(3 + i, values[i]);
            dirty = false;
          });
      row(
          166,
          "上锁战利品："
              + (!data.contains("CollectLocked") || data.getBoolean("CollectLocked") ? "开启" : "关闭"),
          () -> send(9, 0));
      row(
          192,
          "展示框物品："
              + (!data.contains("CollectFrames") || data.getBoolean("CollectFrames") ? "开启" : "关闭"),
          () -> send(10, 0));
    } else if (tab == 2) {
      int half = (imageWidth - 28) / 2;
      categoryButton = fixed(12, contentTop, half, "分类：" + RecentItems.CATEGORIES[category], this::toggleCategories);
      if (choosingCategory) setFocused(categoryButton);
      fixed(16 + half, contentTop, half, "刷新", () -> { loadRecent(historyPage); init(); });
      var previous = fixed(12, imageHeight - 26, 22, "<", () -> { loadRecent(historyPage - 1); init(); });
      var next = fixed(38, imageHeight - 26, 22, ">", () -> { loadRecent(historyPage + 1); init(); });
      previous.active = history != null && historyWait == 0 && historyPage > 0;
      next.active = history != null && historyWait == 0 && historyPage + 1 < history.getInt("Pages");
    } else if (tab == 3) {
      var list = data.getList("Excluded", 10);
      int pages = Math.max(1, (list.size() + 2) / 3);
      page = Math.min(page, pages - 1);
      for (int i = 0; i < 3; i++) {
        int index = page * 3 + i;
        if (index >= list.size()) break;
        row(imageWidth - 62, i * 30, 50, "移除", () -> send(6, index));
      }
      row(
          12,
          96,
          62,
          "上一页",
          () -> {
            page = Math.max(0, page - 1);
            init();
          });
      row(
          78,
          96,
          62,
          "下一页",
          () -> {
            page = Math.min(pages - 1, page + 1);
            init();
          });
      row(
          144,
          96,
          imageWidth - 156,
          confirm ? "确认清空？" : "清空名单",
          () -> {
            if (confirm) {
              send(7, 0);
              confirm = false;
            } else {
              confirm = true;
              init();
            }
          });
    } else {
      row(
          0,
          "错误／长期跳过记录 (" + data.getInt("FaultSkippedTotal") + ")",
          () -> ErrorScreen.openHistory(menu.identity));
      var recovery = row(26, "取回安全恢复缓存", () -> send(8, 0));
      if (recovery != null) recovery.active = data.contains("Recovery");
    }
  }

  private void adjust(int axis, int delta) {
    try {
      draft[axis] = "" + Math.max(0, Math.min(max, Integer.parseInt(draft[axis]) + delta));
      dirty = true;
      init();
    } catch (NumberFormatException ex) {
      status = "请输入有效半径";
    }
  }

  @Override
  protected void renderBg(GuiGraphics g, float p, int mx, int my) {
    g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, 0xffb18c4d);
    g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff20242a);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mx, int my) {
    g.drawString(font, "宝箱磁铁", 12, 9, 0xffe8bd68, false);
    g.enableScissor(
        leftPos + 8, topPos + contentTop, leftPos + imageWidth - 8, topPos + contentBottom);
    if (tab == 0) {
      var t = data.getCompound("StructureTask");
      text(g, "与具有相同功能的模组同时使用时可能冲突", 98, 0xffe8bd68);
      text(g, "携带即检测，关闭吸附不影响", 111, 0xffc9bda6);
      text(g, "最新指南针目标附近32格内自动收取", 123, 0xffc9bda6);
      text(g, "范围包含重叠容器，遵循背包销毁规则", 135, 0xffc9bda6);
      text(g, t.getString("Status").isBlank() ? "结构任务：暂无" : t.getString("Status"), 148, 0xffe8bd68);
      text(
          g,
          "区块 " + t.getInt("Done") + "/" + t.getInt("Total") + " · 已处理 " + t.getInt("Processed"),
          161,
          0xfff3ead4);
    } else if (tab == 1) {
      text(
          g,
          (radius("X") * 2 + 1)
              + " × "
              + (radius("Y") * 2 + 1)
              + " × "
              + (radius("Z") * 2 + 1)
              + " 格 · 每轴0–"
              + max,
          3,
          0xffe8bd68);
      for (int i = 0; i < 3; i++)
        text(g, new String[] {"东西 X · 半径", "上下 Y · 半径", "南北 Z · 半径"}[i], 30 + i * 26, 0xfff3ead4);
      text(g, "半径只影响附近吸附，不改变结构范围", 135, 0xffc9bda6);
      text(g, "收取来源", 151, 0xffe8bd68);
      text(g, "收空后：天境解锁；密码锁清除", 221, 0xffc9bda6);
      text(g, "冰冻箱留普通空箱；Tetra 容器拆除", 237, 0xffc9bda6);
      text(g, "展示框保留空框；Lootr 保持个人库存", 253, 0xffc9bda6);
    } else if (tab == 2) {
      drawRecent(g);
    } else if (tab == 3) {
      var list = data.getList("Excluded", 10);
      if (list.isEmpty()) text(g, "暂无排除记录；手持磁铁对准来源添加", 8, 0xffc9bda6);
      for (int i = 0; i < 3 && page * 3 + i < list.size(); i++) {
        String label = list.getCompound(page * 3 + i).getString("Label");
        int yy = contentTop + 6 + i * 30 - scroll;
        if (yy >= contentTop && yy + 10 < contentBottom)
          g.drawString(
              font, font.plainSubstrByWidth(label, imageWidth - 88), 14, yy, 0xfff3ead4, false);
      }
      text(g, "第 " + (page + 1) + " 页 · 排除记录不等于错误跳过", 128, 0xffc9bda6);
    } else {
      text(g, "异常来源长期跳过，手动解除后才可重试", 64, 0xffc9bda6);
      text(g, "不确定批次封存，不自动补发或删除", 80, 0xffc9bda6);
      text(g, "报告：logs/chestmagnet/errors", 96, 0xffc9bda6);
    }
    g.disableScissor();
    g.drawString(
        font,
        font.plainSubstrByWidth(status, imageWidth - 28),
        14,
        imageHeight - 39,
        0xffe8bd68,
        false);
    if (tab == 2) {
      String paging = history == null ? "—" : (historyPage + 1) + "/" + history.getInt("Pages");
      g.drawString(font, paging, 66, imageHeight - 20, 0xffc9bda6, false);
    } else if (contentBottom - contentTop < contentHeight)
      g.drawString(font, "滚轮查看更多", 12, imageHeight - 20, 0xffc9bda6, false);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    if (tab == 2 && choosingCategory) {
      categoryOffset = net.minecraft.util.Mth.clamp(categoryOffset - (int) Math.signum(delta),
          0, RecentItems.CATEGORIES.length - dropdownRows());
      return true;
    }
    if (y >= topPos + contentTop && y < topPos + contentBottom) {
      scroll =
          Math.max(
              0,
              Math.min(Math.max(0, contentHeight - (contentBottom - contentTop)), scroll - (int) delta * 24));
      init();
      return true;
    }
    return super.mouseScrolled(x, y, delta);
  }

  @Override
  public void render(GuiGraphics g, int x, int y, float p) {
    renderBackground(g);
    super.render(g, x, y, p);
    if (tab == 2) {
      drawCategoryControl(g, x, y);
      if (!choosingCategory) recentTooltip(g, x, y);
    }
    renderTooltip(g, x, y);
  }

  private void toggleCategories() {
    choosingCategory = !choosingCategory;
    categoryCursor = category;
    categoryOffset = Math.max(0, category - dropdownRows() + 1);
    setFocused(categoryButton);
  }

  private int dropdownRows() {
    return Math.min(RecentItems.CATEGORIES.length, Math.max(1, (contentBottom - contentTop - 24) / 17));
  }

  private void chooseCategory(int next) {
    category = next;
    choosingCategory = false;
    loadRecent(0);
    init();
    setFocused(categoryButton);
  }

  private void drawCategoryControl(GuiGraphics g, int x, int y) {
    if (categoryButton == null) return;
    int left = categoryButton.getX(), width = categoryButton.getWidth();
    int arrowX = left + width - 10, arrowY = categoryButton.getY() + 9;
    for (int i = 0; i < 3; i++) {
      int inset = choosingCategory ? 2 - i : i;
      g.fill(arrowX + inset, arrowY + i, arrowX + 5 - inset, arrowY + i + 1, 0xffe8bd68);
    }
    if (!choosingCategory) return;
    int top = categoryButton.getY() + 22;
    int count = dropdownRows();
    g.pose().pushPose();
    g.pose().translate(0, 0, 400);
    g.fill(left, top, left + width, top + count * 17 + 2, 0xffb18c4d);
    for (int i = 0; i < count; i++) {
      int option = categoryOffset + i, yy = top + 1 + i * 17;
      boolean focused = option == categoryCursor || x >= left && x < left + width && y >= yy && y < yy + 17;
      g.fill(left + 1, yy, left + width - 1, yy + 17, focused ? 0xff51432e : 0xff20242a);
      g.drawString(font, RecentItems.CATEGORIES[option], left + 7, yy + 4,
          option == category ? 0xffe8bd68 : 0xfff3ead4, false);
    }
    g.pose().popPose();
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (tab == 2 && choosingCategory && button == 0) {
      int first = categoryButton.getY() + 23;
      if (x >= categoryButton.getX() && x < categoryButton.getX() + categoryButton.getWidth()
          && y >= first && y < first + dropdownRows() * 17)
        chooseCategory(categoryOffset + (int) (y - first) / 17);
      else choosingCategory = false;
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (tab == 2 && choosingCategory) {
      switch (key) {
        case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> choosingCategory = false;
        case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE,
            org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> chooseCategory(categoryCursor);
        case org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> {
          categoryCursor = net.minecraft.util.Mth.clamp(categoryCursor
              + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, RecentItems.CATEGORIES.length - 1);
          categoryOffset = net.minecraft.util.Mth.clamp(categoryOffset,
              Math.max(0, categoryCursor - dropdownRows() + 1), categoryCursor);
          playCategoryNarration();
        }
        default -> { return true; }
      }
      return true;
    }
    return super.keyPressed(key, scanCode, modifiers);
  }

  private void playCategoryNarration() {
    if (minecraft != null) minecraft.getNarrator().sayNow(Component.literal(RecentItems.CATEGORIES[categoryCursor]));
  }

  private void loadRecent(int page) {
    historyPage = Math.max(0, Math.min(31, page));
    history = null;
    recentRows = java.util.List.of();
    scroll = 0;
    historyWait = 60;
    historyRequest = (++historySequence << 8) | (historyPage << 3) | category;
    send(19, historyRequest);
  }

  static void receiveRecent(Net.RecentPage message) {
    if (net.minecraft.client.Minecraft.getInstance().screen instanceof MagnetScreen screen
        && screen.tab == 2 && message.identity().equals(screen.menu.identity)
        && message.menuId() == screen.menu.containerId && message.request() == screen.historyRequest && message.data() != null) {
      screen.history = message.data();
      screen.recentRows = screen.readRecentRows();
      screen.historyPage = screen.history.getInt("Page");
      screen.historyWait = 0;
      screen.init();
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (historyWait > 0) historyWait--;
  }

  private static final java.time.format.DateTimeFormatter CLOCK =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());

  private static net.minecraft.world.item.ItemStack icon(CompoundTag display) {
    var id = net.minecraft.resources.ResourceLocation.tryParse(display.getString("Item"));
    var item = id == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
    if (item == null || item == net.minecraft.world.item.Items.AIR) return net.minecraft.world.item.ItemStack.EMPTY;
    var stack = new net.minecraft.world.item.ItemStack(item);
    if (display.contains("Damage")) stack.setDamageValue(display.getInt("Damage"));
    var enchants = display.getList("Enchants", 10);
    if (!enchants.isEmpty()) stack.getOrCreateTag().put(
        item instanceof net.minecraft.world.item.EnchantedBookItem ? "StoredEnchantments" : "Enchantments", enchants.copy());
    return stack;
  }

  private static String itemName(CompoundTag display, net.minecraft.world.item.ItemStack icon) {
    if (display.contains("Name")) return display.getString("Name");
    String name = icon.isEmpty() ? display.getString("Item") : icon.getHoverName().getString();
    return display.contains("Variant") ? name + " · " + display.getString("Variant") : name;
  }

  private static String receipt(int mode) {
    return Data.modeName(mode) + " · " + switch (mode) {
      case 1, 3 -> "网络已接收";
      case 2 -> "已处理";
      default -> "已存入";
    };
  }

  private java.util.List<RecentRow> readRecentRows() {
    var rows = history.getList("Rows", 10);
    var decoded = new java.util.ArrayList<RecentRow>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.getCompound(i);
      var display = row.getCompound("Display");
      var icon = icon(display);
      String name = itemName(display, icon);
      var lines = new java.util.ArrayList<Component>();
      lines.add(Component.literal(name));
      net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(icon)
          .forEach((enchant, level) -> lines.add(enchant.getFullname(level)));
      var amounts = row.getLongArray("Destinations");
      int destinations = 0;
      int lastMode = -1;
      for (int mode = 0; mode < Math.min(4, amounts.length); mode++) {
        if (amounts[mode] <= 0) continue;
        destinations++;
        lastMode = mode;
        lines.add(Component.literal(receipt(mode) + " ×" + amounts[mode]
            + (mode == 2 ? "（可能压缩或销毁）" : "")));
      }
      String detail = destinations > 1 ? destinations + " 个去向 · 悬停查看" :
          lastMode >= 0 ? receipt(lastMode) : "已处理";
      decoded.add(new RecentRow(icon, name, "×" + row.getLong("Count"),
          detail + "  " + CLOCK.format(java.time.Instant.ofEpochMilli(row.getLong("Time"))), lines));
    }
    return decoded;
  }

  private void drawRecent(GuiGraphics g) {
    g.enableScissor(leftPos + 8, topPos + contentTop + 23, leftPos + imageWidth - 8,
        topPos + Math.max(contentTop + 38, contentBottom));
    g.drawString(font, "收取记录，不代表当前库存", 14, contentTop + 25, 0xffc9bda6, false);
    if (history == null || history.getInt("Total") == 0) {
      String empty = history == null ? (historyWait > 0 ? "正在读取…" : "未收到记录，请点击刷新") : "这个分类暂无记录";
      g.drawString(font, empty, 14, contentTop + 47, 0xfff3ead4, false);
    } else {
      g.enableScissor(leftPos + 8, topPos + contentTop + 38, leftPos + imageWidth - 8,
          topPos + Math.max(contentTop + 38, contentBottom));
      for (int i = 0; i < recentRows.size(); i++) {
        int y = contentTop + 42 + i * 27 - scroll;
        if (y + 25 < contentTop + 36 || y >= contentBottom) continue;
        var row = recentRows.get(i);
        if (i % 2 == 0) g.fill(12, y, imageWidth - 12, y + 25, 0xff292d34);
        g.renderItem(row.icon(), 15, y + 4);
        String amount = row.amount();
        int ax = imageWidth - 17 - font.width(amount);
        g.drawString(font, amount, ax, y + 3, 0xffe8bd68, false);
        g.drawString(font, font.plainSubstrByWidth(row.name(), Math.max(0, ax - 39)),
            35, y + 3, 0xfff3ead4, false);
        g.drawString(font, font.plainSubstrByWidth(row.detail(), imageWidth - 50),
            35, y + 14, 0xffc9bda6, false);
      }
      g.disableScissor();
    }
    g.disableScissor();
  }

  private void recentTooltip(GuiGraphics g, int x, int y) {
    if (history == null || x < leftPos + 12 || x >= leftPos + imageWidth - 12
        || y < topPos + contentTop + 36 || y >= topPos + contentBottom) return;
    int offset = y - topPos - contentTop - 42 + scroll;
    if (offset < 0 || offset / 27 >= recentRows.size() || offset % 27 >= 25) return;
    g.renderComponentTooltip(font, recentRows.get(offset / 27).tooltip(), x, y);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
