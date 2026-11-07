package dev.chestmagnet;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class MagnetScreen extends AbstractContainerScreen<MagnetMenu> {
  private CompoundTag data = new CompoundTag();
  private String status = "同步中…";
  private int max = 64, tab, page;
  private boolean confirm, dirty;
  private final EditBox[] axes = new EditBox[3];
  private Button mode, toggle;

  public MagnetScreen(MagnetMenu menu, Inventory inv, Component title) {
    super(menu, inv, title);
    imageWidth = 310;
    imageHeight = 230;
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
    boolean changed = !s.data().getList("Excluded", 10).equals(data.getList("Excluded", 10));
    data = s.data();
    status = s.status();
    max = s.maxRadius();
    if (tab == 1 && changed && minecraft != null) init();
    if (tab == 0 && !dirty)
      for (int i = 0; i < 3; i++)
        if (axes[i] != null) axes[i].setValue("" + radius(new String[] {"X", "Y", "Z"}[i]));
  }

  private void send(int action, int value) {
    Net.request(action, value, menu.identity);
  }

  private Button button(int x, int y, int w, String text, Runnable action) {
    var label = Component.literal(text);
    if ((text.equals("设置") && tab == 0) || (text.startsWith("排除容器") && tab == 1))
      label.withStyle(net.minecraft.ChatFormatting.GOLD);
    var b = Button.builder(label, v -> action.run()).bounds(leftPos + x, topPos + y, w, 20).build();
    addRenderableWidget(b);
    return b;
  }

  @Override
  protected void init() {
    imageWidth = Math.min(350, width - 12);
    imageHeight = Math.min(230, height - 8);
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
    button(
        12,
        27,
        76,
        "设置",
        () -> {
          tab = 0;
          confirm = false;
          dirty = false;
          init();
        });
    button(
        92,
        27,
        110,
        "排除容器 (" + data.getList("Excluded", 10).size() + ")",
        () -> {
          tab = 1;
          confirm = false;
          init();
        });
    button(imageWidth - 66, 27, 54, "完成", this::onClose);
    if (tab == 0) {
      toggle = button(12, 55, 84, "", () -> send(2, 0));
      mode = button(100, 55, imageWidth - 112, "", () -> send(1, data.getInt("Mode") + 1));
      for (int i = 0; i < 3; i++) {
        final int axis = i;
        int y = 88 + i * 25;
        button(
            imageWidth - 128,
            y,
            22,
            "−",
            () -> send(3 + axis, radius(new String[] {"X", "Y", "Z"}[axis]) - 1));
        var field =
            new EditBox(
                font,
                leftPos + imageWidth - 102,
                topPos + y,
                54,
                20,
                Component.literal(new String[] {"X半径", "Y半径", "Z半径"}[i]));
        field.setMaxLength(3);
        field.setFilter(v -> v.matches("[0-9]{0,3}"));
        field.setValue("" + radius(new String[] {"X", "Y", "Z"}[i]));
        field.setResponder(v -> dirty = true);
        axes[i] = field;
        addRenderableWidget(field);
        button(
            imageWidth - 44,
            y,
            32,
            "+",
            () -> send(3 + axis, radius(new String[] {"X", "Y", "Z"}[axis]) + 1));
      }
      button(
          imageWidth - 96,
          166,
          84,
          "应用范围",
          () -> {
            for (int i = 0; i < 3; i++) {
              try {
                int v = Integer.parseInt(axes[i].getValue());
                send(3 + i, Math.min(max, v));
              } catch (NumberFormatException ignored) {
                status = "请输入有效的非负整数";
                return;
              }
            }
            dirty = false;
          });
      if (data.contains("Recovery")) button(12, 166, 104, "取回恢复缓存", () -> send(8, 0));
    } else {
      var list = data.getList("Excluded", 10);
      int pages = Math.max(1, (list.size() + 3) / 4);
      page = Math.min(page, pages - 1);
      for (int i = 0; i < 4; i++) {
        int index = page * 4 + i;
        if (index >= list.size()) break;
        button(imageWidth - 58, 58 + i * 29, 46, "移除", () -> send(6, index));
      }
      button(
          12,
          179,
          50,
          "上一页",
          () -> {
            page = Math.max(0, page - 1);
            confirm = false;
            init();
          });
      button(
          66,
          179,
          50,
          "下一页",
          () -> {
            page = Math.min(pages - 1, page + 1);
            confirm = false;
            init();
          });
      button(
          imageWidth - 122,
          179,
          110,
          confirm ? "确认清空全部？" : "清空排除列表",
          () -> {
            if (confirm) {
              send(7, 0);
              confirm = false;
            } else {
              confirm = true;
              init();
            }
          });
    }
  }

  @Override
  protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
    g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, 0xffb18c4d);
    g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff20242a);
    g.fill(leftPos + 8, topPos + 22, leftPos + imageWidth - 8, topPos + 23, 0xff776345);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    g.drawString(font, "宝箱磁铁", 12, 9, 0xffe8bd68, false);
    g.drawString(font, tab == 0 ? "配置" : "排除记录", imageWidth - 67, 9, 0xffc9bda6, false);
    if (tab == 0) {
      if (mode != null)
        mode.setMessage(Component.literal("吸收模式：" + Data.modeName(data.getInt("Mode"))));
      if (toggle != null) toggle.setMessage(Component.literal(enabled() ? "已开启" : "已关闭"));
      String[] labels = {"东西 X · 半径", "上下 Y · 半径", "南北 Z · 半径"};
      for (int i = 0; i < 3; i++) g.drawString(font, labels[i], 14, 94 + i * 25, 0xfff3ead4, false);
      g.drawString(
          font,
          (radius("X") * 2 + 1)
              + " × "
              + (radius("Y") * 2 + 1)
              + " × "
              + (radius("Z") * 2 + 1)
              + " 格",
          14,
          78,
          0xffe8bd68,
          false);
      if (!data.contains("Recovery"))
        g.drawString(font, "每轴 0–" + max + " 格", 14, 172, 0xffc9bda6, false);
      g.drawWordWrap(
          font,
          Component.literal(data.getInt("Mode") == 2 ? "背包遵循过滤及升级规则，包含销毁。" : "右键启停；潜行左键空气打开此页面。"),
          14,
          192,
          imageWidth - 28,
          0xffc9bda6);
    } else {
      var list = data.getList("Excluded", 10);
      if (list.isEmpty())
        g.drawWordWrap(
            font,
            Component.literal("暂无排除记录。手持磁铁，潜行右键容器或储物矿车即可添加。"),
            16,
            65,
            imageWidth - 32,
            0xffc9bda6);
      for (int i = 0; i < 4 && page * 4 + i < list.size(); i++) {
        String label = list.getCompound(page * 4 + i).getString("Label");
        g.drawWordWrap(
            font,
            Component.literal(font.plainSubstrByWidth(label, (imageWidth - 90) * 2)),
            14,
            60 + i * 29,
            imageWidth - 84,
            0xfff3ead4);
      }
      g.drawString(font, "第 " + (page + 1) + " 页 · 矿车记录随实体移动生效", 14, 205, 0xffc9bda6, false);
    }
    if (tab == 0)
      g.drawString(
          font,
          font.plainSubstrByWidth(status, imageWidth - 28),
          14,
          imageHeight - 13,
          0xffe8bd68,
          false);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    renderBackground(g);
    super.render(g, mouseX, mouseY, partial);
    renderTooltip(g, mouseX, mouseY);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
