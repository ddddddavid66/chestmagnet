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
  private final EditBox[] axes = new EditBox[3];
  private final String[] draft = new String[3];
  private int contentTop, contentBottom;
  private static final String[] TABS = {"常规", "范围", "来源", "排除", "诊断"};

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
    if (!(tab == 1 && dirty) && minecraft != null) init();
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
    int columns = imageWidth < 270 ? 3 : 5, rows = (5 + columns - 1) / columns;
    int tw = (imageWidth - 24 - (columns - 1) * 4) / columns;
    for (int i = 0; i < 5; i++) {
      final int t = i;
      var b =
          fixed(
              12 + (i % columns) * (tw + 4),
              27 + (i / columns) * 24,
              tw,
              TABS[i],
              () -> {
                tab = t;
                scroll = 0;
                confirm = false;
                dirty = false;
                init();
              });
      if (tab == i)
        b.setMessage(Component.literal(TABS[i]).withStyle(net.minecraft.ChatFormatting.GOLD));
    }
    if (!dirty) for (int i = 0; i < 3; i++) draft[i] = "" + radius(new String[] {"X", "Y", "Z"}[i]);
    contentTop = 27 + rows * 24 + 4;
    contentBottom = imageHeight - 42;
    fixed(imageWidth - 68, imageHeight - 26, 56, "完成", this::onClose);
    if (tab == 0) {
      row(0, enabled() ? "磁铁：已开启" : "磁铁：已关闭", () -> send(2, 0));
      row(24, "吸收模式：" + Data.modeName(data.getInt("Mode")), () -> send(1, data.getInt("Mode") + 1));
      row(48, "开启自动吸收结构的宝箱：" + (data.getBoolean("AutoStructure") ? "是" : "否"), () -> send(15, 0));
      var task = data.getCompound("StructureTask");
      var cont = row(12, 128, (imageWidth - 28) / 2, "继续任务", () -> send(16, 0));
      if (cont != null) cont.active = task.getBoolean("CanContinue");
      var stop =
          row(16 + (imageWidth - 28) / 2, 128, (imageWidth - 28) / 2, "停止任务", () -> send(17, 0));
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
    } else if (tab == 2) {
      row(
          0,
          "上锁战利品："
              + (!data.contains("CollectLocked") || data.getBoolean("CollectLocked") ? "开启" : "关闭"),
          () -> send(9, 0));
      row(
          26,
          "展示框物品："
              + (!data.contains("CollectFrames") || data.getBoolean("CollectFrames") ? "开启" : "关闭"),
          () -> send(10, 0));
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
      text(g, "最新指南针目标附近32格内自动收取", 76, 0xffc9bda6);
      text(g, "范围包含重叠容器，遵循背包销毁规则", 89, 0xffc9bda6);
      text(g, t.getString("Status").isBlank() ? "结构任务：暂无" : t.getString("Status"), 101, 0xffe8bd68);
      text(
          g,
          "区块 " + t.getInt("Done") + "/" + t.getInt("Total") + " · 已处理 " + t.getInt("Processed"),
          114,
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
      text(g, "只影响附近吸附，不改变结构收取范围", 136, 0xffc9bda6);
    } else if (tab == 2) {
      text(g, "收空后：天境解锁；密码锁清除", 62, 0xffc9bda6);
      text(g, "冰冻箱留普通空箱；Tetra容器拆除", 77, 0xffc9bda6);
      text(g, "展示框保留空框；Lootr保持个人库存", 92, 0xffc9bda6);
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
    if (contentBottom - contentTop < 156)
      g.drawString(font, "滚轮查看更多", 12, imageHeight - 20, 0xffc9bda6, false);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    if (y >= topPos + contentTop && y < topPos + contentBottom) {
      scroll =
          Math.max(
              0,
              Math.min(Math.max(0, 156 - (contentBottom - contentTop)), scroll - (int) delta * 24));
      init();
      return true;
    }
    return super.mouseScrolled(x, y, delta);
  }

  @Override
  public void render(GuiGraphics g, int x, int y, float p) {
    renderBackground(g);
    super.render(g, x, y, p);
    renderTooltip(g, x, y);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
