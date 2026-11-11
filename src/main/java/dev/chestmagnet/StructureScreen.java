package dev.chestmagnet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** 收取进度与现场楼层图；地图视窗绝不反向决定全高度收取范围。 */
public final class StructureScreen extends Screen {
  private static java.lang.ref.WeakReference<StructureScreen> activePreview = new java.lang.ref.WeakReference<>(null);
  CompoundTag data;
  final PlanMap map = new PlanMap();
  int x, y, w, h, markerIndex;
  float zoom = 1;
  double panX, panZ;
  boolean cancelExplicit, dragging;
  Button start, mode;
  EditBox slice;

  StructureScreen(CompoundTag snapshot) {
    super(Component.literal("结构收取"));
    data = snapshot;
    activePreview = new java.lang.ref.WeakReference<>(this);
    map.receive(snapshot);
    if (!snapshot.contains("MapY")) map.y = snapshot.getInt("Slice");
  }

  public static void receive(CompoundTag snapshot) {
    if (snapshot == null) return;
    var mc = Minecraft.getInstance();
    if (snapshot.contains("Error")) {
      mc.setScreen(new AlertScreen(() -> mc.setScreen(null),
          Component.literal("宝箱磁铁"), Component.literal(snapshot.getString("Error"))));
      return;
    }
    // 查看错误详情时仍更新上一级快照；否则返回地图会永久缺失这段时间收到的图块。
    StructureScreen screen = mc.screen instanceof StructureScreen visible ? visible : activePreview.get();
    if (screen != null && snapshot.hasUUID("Nonce")
        && screen.data.hasUUID("Nonce")
        && screen.data.getUUID("Nonce").equals(snapshot.getUUID("Nonce"))) {
      screen.update(snapshot);
      if (snapshot.getBoolean("Open") && !(mc.screen instanceof StructureScreen)
          && !(mc.screen instanceof ErrorScreen)) mc.setScreen(screen);
    } else if (snapshot.getBoolean("Open")) {
      mc.setScreen(new StructureScreen(snapshot));
    }
  }

  void update(CompoundTag snapshot) {
    map.receive(snapshot);
    if (snapshot.contains("MapHeader")) {
      if (slice != null && !slice.isFocused()) slice.setValue("" + map.y);
      return;
    }
    if (snapshot.contains("MapColors") || snapshot.contains("MapPieces")) return;
    data = snapshot;
    if (start != null) refresh();
  }

  private void send(int action, int value) { Net.request(action, value, data.getUUID("Nonce")); }

  private void requestView(int centerX, int centerZ, int height) {
    int[] region = data.getIntArray("Region"), body = data.getIntArray("Body");
    if (region.length == 6) height = Math.max(region[1], Math.min(region[4], height));
    if (body.length == 6) {
      centerX = Math.max(body[0], Math.min(body[3], centerX));
      centerZ = Math.max(body[2], Math.min(body[5], centerZ));
    }
    int revision = map.expect(centerX, centerZ, height);
    panX = panZ = 0;
    slice.setValue("" + height);
    Net.previewRequest(data.getUUID("Nonce"), revision, centerX, centerZ, height);
  }

  private int requestedHeight() {
    try { return Integer.parseInt(slice.getValue()); }
    catch (NumberFormatException ignored) { slice.setValue("" + map.y); return map.y; }
  }

  private void height(int value) { requestView(map.x + PlanMap.SIZE / 2, map.z + PlanMap.SIZE / 2, value); }

  @Override
  protected void init() {
    w = Math.min(620, width - 12);
    h = Math.min(410, height - 10);
    x = (width - w) / 2;
    y = (height - h) / 2;
    int row = y + 60;
    slice = new EditBox(font, x + 20, row + 1, 42, 18, Component.literal("楼层高度Y"));
    slice.setMaxLength(6);
    slice.setValue("" + map.y);
    addRenderableWidget(slice);
    addButton("-", x + 66, row, 18, b -> height(map.y - 1));
    addButton("+", x + 88, row, 18, b -> height(map.y + 1));
    addButton("跳转", x + 110, row, 34, b -> height(requestedHeight()));
    addButton("容器楼层", x + 148, row, 62, b -> focusContainer());
    addButton("居中", x + 214, row, 42, b -> center());
    addButton("错误记录", x + w - 76, y + 5, 68, b -> openErrors());
    int bottom = y + h - 26;
    int first = Math.max(84, Math.min(126, (w - 104) / 3));
    int second = Math.max(100, Math.min(158, w - first - 104));
    start = addButton("确定并吸附", x + 8, bottom, first,
        b -> send(data.getBoolean("Running") ? 24 : 21, 0));
    mode = addButton("", x + first + 12, bottom, second,
        b -> send(23, data.getInt("Mode") + 1));
    addButton("取消任务", x + w - 80, bottom, 72, b -> {
      cancelExplicit = true;
      send(22, 0);
      onClose();
    });
    refresh();
  }

  private Button addButton(String label, int left, int top, int width, Button.OnPress click) {
    return addRenderableWidget(Button.builder(Component.literal(label), click)
        .bounds(left, top, width, 20).build());
  }

  private void focusContainer() {
    long[] points = data.getLongArray("Containers");
    if (points.length == 0) return;
    BlockPos pos = BlockPos.of(points[Math.floorMod(markerIndex++, points.length)]);
    requestView(pos.getX(), pos.getZ(), pos.getY() + 1);
  }

  private void center() {
    int[] body = data.getIntArray("Body");
    if (body.length != 6) return;
    zoom = 1;
    requestView((body[0] + body[3]) / 2, (body[2] + body[5]) / 2, requestedHeight());
  }

  private void openErrors() {
    var player = Minecraft.getInstance().player;
    if (player == null) return;
    var held = player.getMainHandItem().is(ChestMagnet.MAGNET.get())
        ? player.getMainHandItem() : player.getOffhandItem();
    if (held.is(ChestMagnet.MAGNET.get())) ErrorScreen.openHistory(ClientEvents.identity(held));
  }

  void refresh() {
    boolean running = data.getBoolean("Running"), paused = data.getBoolean("Paused");
    start.setMessage(Component.literal(running ? (paused ? "继续收取" : "收取中…") : "确定并吸附"));
    start.active = !running || paused;
    mode.setMessage(Component.literal("目的地：" + Data.modeName(data.getInt("Mode"))));
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    renderBackground(g);
    g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xffb18c4d);
    g.fill(x, y, x + w, y + h, 0xff20242a);
    String key = data.getString("Structure");
    String name = net.minecraft.client.resources.language.I18n.get("structure." + key.replace(':', '.'));
    if (name.startsWith("structure.")) name = key;
    g.drawString(font, font.plainSubstrByWidth("结构收取：" + name, w - 96), x + 8, y + 8, 0xffe8bd68, false);
    g.drawString(font, "扫描：分段并集＋全高度  |  预览：现场楼层 Y=" + map.y,
        x + 8, y + 24, 0xfff3ead4, false);
    g.drawWordWrap(font, Component.literal("可能收取重叠建筑容器；切换预览楼层不限制吸收范围。"),
        x + 8, y + 38, w - 16, 0xffe8bd68);
    g.drawString(font, "Y", x + 8, y + 66, 0xfff3ead4, false);
    int mapTop = y + 87, mapHeight = Math.max(30, h - 151);
    boolean side = w >= 480;
    int mapWidth = w - 16 - (side ? 122 : 0);
    map.draw(g, x + 8, mapTop, mapWidth, mapHeight, zoom, panX, panZ, data.getLongArray("Containers"));
    if (side) drawLegend(g, x + w - 122, mapTop, 114, mapHeight);
    else g.drawString(font, "暗:墙 亮:地板 金:容器 斜线:未知", x + 12, mapTop + 4, 0xfff3ead4, false);
    String point = map.contains(mouseX, mouseY) ? map.at(mouseX, mouseY)
        : data.getString("Status");
    g.drawString(font, font.plainSubstrByWidth(point, w - 16), x + 8, y + h - 59, 0xffc9bda6, false);
    String stats = "检查 " + data.getInt("Done") + "/" + data.getInt("Total")
        + "  就绪 " + data.getInt("Ready") + "  等待 " + data.getInt("Waiting")
        + "  来源 " + data.getInt("Found") + "  处理 " + data.getInt("Processed")
        + "  跳过 " + data.getInt("Skipped");
    g.drawString(font, font.plainSubstrByWidth(stats, w - 16), x + 8, y + h - 46, 0xfff3ead4, false);
    super.render(g, mouseX, mouseY, partial);
    if (mouseY >= y + h - 59 && mouseY < y + h - 34 && mouseX >= x && mouseX <= x + w)
      g.renderTooltip(font, Component.literal(data.getString("Status")), mouseX, mouseY);
  }

  private void drawLegend(GuiGraphics g, int left, int top, int width, int height) {
    g.drawString(font, "完整分段布局", left, top, 0xffe8bd68, false);
    int overviewHeight = Math.max(30, Math.min(102, height - 74));
    map.overview(g, left, top + 14, width, overviewHeight, data.getIntArray("Body"));
    int at = top + overviewHeight + 20;
    String[] lines = {"示意边界，非实际墙体", "暗色：墙体／实心", "亮色：当前层地板",
        "金色：附近楼层容器", "青色：玩家／当前视窗", "斜线：未知，不是空气"};
    for (String line : lines) {
      if (at + 9 > top + height) break;
      g.drawString(font, line, left, at, 0xffc9bda6, false);
      at += 12;
    }
    if (map.simplified && at + 9 <= top + height)
      g.drawString(font, "分段过多：区块级示意", left, at, 0xffe8bd68, false);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0 && map.contains(mouseX, mouseY)) { dragging = true; return true; }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
    if (button == 0 && dragging) { panX += dx; panZ += dy; return true; }
    return super.mouseDragged(mouseX, mouseY, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (button == 0 && dragging) {
      dragging = false;
      if (Math.abs(panX) + Math.abs(panZ) >= 2)
        requestView(map.x + PlanMap.SIZE / 2 - map.shiftX(panX),
            map.z + PlanMap.SIZE / 2 - map.shiftZ(panZ), requestedHeight());
      else panX = panZ = 0;
      return true;
    }
    return super.mouseReleased(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    if (!map.contains(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, delta);
    zoom = Math.max(0.5f, Math.min(8, zoom + (float) delta * 0.25f));
    return true;
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (slice.isFocused() && (key == 257 || key == 335)) { height(requestedHeight()); return true; }
    return super.keyPressed(key, scan, modifiers);
  }

  public void reloadPreview() { map.close(); }

  @Override
  public void onClose() {
    if (!cancelExplicit) send(26, 0);
    if (activePreview.get() == this) activePreview.clear();
    super.onClose();
  }

  @Override
  public void removed() { map.close(); }

  @Override
  public boolean isPauseScreen() { return false; }
}
