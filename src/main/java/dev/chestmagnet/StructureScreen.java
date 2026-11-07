package dev.chestmagnet;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class StructureScreen extends Screen {
  CompoundTag data;
  final PreviewMesh mesh = new PreviewMesh();
  float yaw = 35, pitch = 28, zoom = 1;
  int x, y, w, h;
  boolean cancelExplicit;
  int markerIndex;
  Button start, mode;
  EditBox slice;

  StructureScreen(CompoundTag d) {
    super(Component.literal("结构收取"));
    data = d;
    mesh.clear(
        d.contains("PreviewBounds") ? d.getIntArray("PreviewBounds") : d.getIntArray("Body"));
    mesh.cut = d.getInt("Slice");
  }

  public static void receive(CompoundTag d) {
    var mc = Minecraft.getInstance();
    if (d == null) return;
    if (d.contains("Error")) {
      mc.setScreen(
          new AlertScreen(
              () -> mc.setScreen(null),
              Component.literal("宝箱磁铁"),
              Component.literal(d.getString("Error"))));
      return;
    }
    if (mc.screen instanceof StructureScreen s
        && d.hasUUID("Nonce")
        && s.data.getUUID("Nonce").equals(d.getUUID("Nonce"))) {
      s.update(d);
      return;
    }
    if (d.getBoolean("Open")) mc.setScreen(new StructureScreen(d));
  }

  void update(CompoundTag d) {
    if (d.contains("Positions")) {
      mesh.add(d.getLongArray("Positions"), d.getIntArray("States"));
      return;
    }
    data = d;
    if (d.getBoolean("Reset")) {
      mesh.clear(
          d.contains("PreviewBounds") ? d.getIntArray("PreviewBounds") : d.getIntArray("Body"));
      mesh.cut = d.getInt("Slice");
    }
    if (start != null) refresh();
  }

  void send(int a, int value) {
    Net.request(a, value, data.getUUID("Nonce"));
  }

  @Override
  protected void init() {
    w = Math.min(540, width - 16);
    h = Math.min(360, height - 12);
    x = (width - w) / 2;
    y = (height - h) / 2;
    int bottom = y + h - 24;
    start =
        addRenderableWidget(
            Button.builder(
                    Component.literal("确定并吸附"), b -> send(data.getBoolean("Running") ? 24 : 21, 0))
                .bounds(x + 8, bottom, 125, 20)
                .build());
    mode =
        addRenderableWidget(
            Button.builder(Component.empty(), b -> send(23, data.getInt("Mode") + 1))
                .bounds(x + 137, bottom, 145, 20)
                .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("取消任务"),
                b -> {
                  cancelExplicit = true;
                  send(22, 0);
                  onClose();
                })
            .bounds(x + w - 84, bottom, 76, 20)
            .build());
    slice = new EditBox(font, x + 8, y + 66, 56, 18, Component.literal("剖面高度"));
    slice.setValue("" + data.getInt("Slice"));
    slice.setMaxLength(6);
    addRenderableWidget(slice);
    addRenderableWidget(
        Button.builder(
                Component.literal("剖切"),
                b -> {
                  try {
                    send(25, Integer.parseInt(slice.getValue()));
                  } catch (NumberFormatException ignored) {
                  }
                })
            .bounds(x + 68, y + 65, 44, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("重置视角"),
                b -> {
                  yaw = 35;
                  pitch = 28;
                  zoom = 1;
                })
            .bounds(x + 116, y + 65, 70, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("容器楼层"),
                b -> {
                  var points = data.getLongArray("Containers");
                  if (points.length > 0) {
                    var at =
                        net.minecraft.core.BlockPos.of(
                            points[Math.floorMod(markerIndex++, points.length)]);
                    slice.setValue("" + (at.getY() + 3));
                    send(25, at.getY() + 3);
                  }
                })
            .bounds(x + 190, y + 65, 70, 20)
            .build());
    refresh();
  }

  void refresh() {
    start.setMessage(
        Component.literal(
            data.getBoolean("Running")
                ? (data.getBoolean("Paused") ? "继续／重新扫描" : "收取中…")
                : "开启并确定吸附"));
    start.active = !data.getBoolean("Running") || data.getBoolean("Paused");
    mode.setMessage(Component.literal("目的地：" + Data.modeName(data.getInt("Mode"))));
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    renderBackground(g);
    g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xffb18c4d);
    g.fill(x, y, x + w, y + h, 0xff20242a);
    String key = data.getString("Structure");
    String translated =
        net.minecraft.client.resources.language.I18n.get("structure." + key.replace(':', '.'));
    if (translated.startsWith("structure.")) translated = key;
    g.drawString(font, "是否搜索当前结构：" + translated + "？", x + 8, y + 8, 0xffe8bd68, false);
    var region = data.getIntArray("Region");
    if (region.length == 6)
      g.drawString(
          font,
          "扫描 X:"
              + region[0]
              + ".."
              + region[3]
              + " Z:"
              + region[2]
              + ".."
              + region[5]
              + " · 全部高度 "
              + region[1]
              + ".."
              + region[4],
          x + 8,
          y + 24,
          0xfff3ead4,
          false);
    g.drawWordWrap(
        font,
        Component.literal("将收取此水平范围内地上、地下容器，可能包含重叠建筑；遵守排除名单与背包销毁规则。"),
        x + 8,
        y + 39,
        w - 16,
        0xffe8bd68);
    int previewY = y + 89, previewH = Math.max(35, h - 144);
    mesh.draw(g, x + 4, previewY, w - 8, previewH, yaw, pitch, zoom);
    g.drawString(font, "现场局部预览 · 拖动旋转／滚轮缩放 · 不限制扫描深度", x + 8, previewY + 4, 0xfff3ead4, false);
    g.drawString(
        font,
        font.plainSubstrByWidth(data.getString("Status"), w - 16),
        x + 8,
        y + h - 51,
        0xffe8bd68,
        false);
    g.drawString(
        font,
        "区块 "
            + data.getInt("Done")
            + "/"
            + data.getInt("Total")
            + " · 容器 "
            + data.getInt("Found")
            + " · 已处理 "
            + data.getInt("Processed"),
        x + 8,
        y + h - 38,
        0xfff3ead4,
        false);
    super.render(g, mx, my, partial);
  }

  @Override
  public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
    if (button == 0 && my > y + 89 && my < y + h - 60) {
      yaw += dx;
      pitch = Math.max(-85, Math.min(85, pitch + (float) dy));
      return true;
    }
    return super.mouseDragged(mx, my, button, dx, dy);
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double delta) {
    zoom = Math.max(.3f, Math.min(4, zoom + (float) delta * .1f));
    return true;
  }

  @Override
  public void onClose() {
    if (!cancelExplicit) send(26, 0);
    super.onClose();
  }

  @Override
  public void removed() {
    mesh.close();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
