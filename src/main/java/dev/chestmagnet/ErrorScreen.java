package dev.chestmagnet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** 仅打开本地已收到的报告；服务器不能通过报告指定客户端路径或启动任意命令。 */
public final class ErrorScreen extends Screen {
  private record LocalReport(Faults.Report report, Path folder, String saveError) {}
  private static final Map<UUID, LocalReport> REPORTS = new LinkedHashMap<>();
  private final LocalReport local;
  private final Screen parent;
  private CompoundTag data = new CompoundTag();
  private List<FormattedCharSequence> lines = List.of();
  private int x, y, w, h, bodyTop, bodyBottom, scroll, tab, page;
  private Button detailsTab, skippedTab;

  private ErrorScreen(LocalReport local, Screen parent) {
    super(Component.literal("宝箱磁铁 · 错误详情"));
    this.local = local;
    this.parent = parent;
    if (ClientEvents.latest != null) update(ClientEvents.latest);
  }

  public static void receive(Faults.Report report) {
    var mc = Minecraft.getInstance();
    Path folder = mc.gameDirectory.toPath().resolve("logs/chestmagnet/errors")
        .resolve(LocalDate.now().toString()).toAbsolutePath().normalize();
    String saveError = "";
    try {
      Files.createDirectories(folder);
      Path target = folder.resolve(report.id() + ".log");
      // 单人游戏服务端可能已在相同路径写下完整报告，不能用网络截断副本覆盖它。
      if (!Files.exists(target)) {
        Path temporary = folder.resolve(report.id() + ".tmp");
        Files.writeString(temporary, report.text(), StandardCharsets.UTF_8);
        Files.move(temporary, target);
      }
    } catch (IOException | SecurityException ex) {
      saveError = "本地日志写入失败：" + ex.getClass().getSimpleName()
          + "；仍可复制下方错误详情。";
      org.slf4j.LoggerFactory.getLogger("chestmagnet").error("Cannot save local report {}", report.id(), ex);
    }
    REPORTS.put(report.id(), new LocalReport(report, folder, saveError));
    // 仅限制本次连接的屏幕缓存，不删除已写入磁盘的任何历史报告。
    while (REPORTS.size() > 128) REPORTS.remove(REPORTS.keySet().iterator().next());
  }

  public static void clearSession() { REPORTS.clear(); }

  public static void open(UUID id) {
    var mc = Minecraft.getInstance();
    var report = REPORTS.get(id);
    if (report == null) {
      if (mc.player != null) mc.player.displayClientMessage(
          Component.literal("本次连接未缓存此报告，请查看 logs/chestmagnet/errors 文件夹"), false);
      return;
    }
    mc.setScreen(new ErrorScreen(report, mc.screen));
    Net.request(14, 0, report.report().magnet());
  }

  /** 菜单入口：没有本次错误报告也可查看、解除故障来源跳过标记。 */
  public static void openHistory(UUID magnet) {
    var mc = Minecraft.getInstance();
    LocalReport report = null;
    for (var value : REPORTS.values()) if (value.report().magnet().equals(magnet)) report = value;
    if (report == null) report = new LocalReport(
        new Faults.Report(new UUID(0, 0), magnet, "本次连接暂无此磁铁的错误报告。\n"
            + "历史日志保存在上方文件夹；下方“跳过来源”可管理持久化故障标记。"),
        mc.gameDirectory.toPath().resolve("logs/chestmagnet/errors").toAbsolutePath().normalize(), "");
    var screen = new ErrorScreen(report, mc.screen);
    screen.tab = 1;
    mc.setScreen(screen);
    Net.request(14, 0, magnet);
  }

  void update(Net.Snapshot snapshot) {
    if (snapshot.data() == null || !snapshot.data().hasUUID("Id")
        || !snapshot.data().getUUID("Id").equals(local.report().magnet())) return;
    data = snapshot.data();
    if (minecraft != null && tab == 1) init();
  }

  private boolean canEdit() {
    var p = Minecraft.getInstance().player;
    if (p == null) return false;
    for (var item : List.of(p.getMainHandItem(), p.getOffhandItem()))
      if (item.is(ChestMagnet.MAGNET.get()) && ClientEvents.identity(item).equals(local.report().magnet())) return true;
    return p.containerMenu instanceof MagnetMenu menu && menu.identity.equals(local.report().magnet());
  }

  private Button button(int bx, int by, int width, String label, Runnable run) {
    return addRenderableWidget(Button.builder(Component.literal(label), b -> run.run())
        .bounds(bx, by, width, 20).build());
  }

  @Override
  protected void init() {
    clearWidgets();
    w = Math.min(610, width - 16);
    h = Math.min(390, height - 16);
    x = (width - w) / 2;
    y = (height - h) / 2;
    bodyTop = y + 103;
    bodyBottom = y + h - 37;
    button(x + 10, y + 50, 90, "打开日志文件夹", () -> {
      try {
        Files.createDirectories(local.folder());
        Util.getPlatform().openFile(local.folder().toFile());
      } catch (IOException | RuntimeException ex) {
        minecraft.keyboardHandler.setClipboard(local.folder().toString());
        if (minecraft.player != null) minecraft.player.displayClientMessage(
            Component.literal("无法打开文件夹，已复制路径"), false);
      }
    });
    button(x + 104, y + 50, 74, "复制路径",
        () -> minecraft.keyboardHandler.setClipboard(local.folder().toString()));
    button(x + 182, y + 50, 90, "复制错误详情",
        () -> minecraft.keyboardHandler.setClipboard(local.report().text()));
    detailsTab = button(x + 10, y + 77, 92, "错误详情", () -> { tab = 0; scroll = 0; init(); });
    skippedTab = button(x + 106, y + 77, 126,
        "跳过来源 (" + data.getInt("FaultSkippedTotal") + ")", () -> { tab = 1; init(); });
    (tab == 0 ? detailsTab : skippedTab).setMessage((tab == 0 ? detailsTab : skippedTab)
        .getMessage().copy().withStyle(net.minecraft.ChatFormatting.GOLD));
    button(x + w - 78, y + h - 27, 68, "返回", this::onClose);
    lines = font.split(Component.literal(local.report().text()
        + (local.saveError().isEmpty() ? "" : "\n\n" + local.saveError())), w - 32);
    if (tab == 1) skippedControls();
    clampScroll();
  }

  private int pageSize() { return Math.max(1, (bodyBottom - bodyTop - 40) / 44); }

  private void skippedControls() {
    var list = data.getList("FaultSkipped", 10);
    int size = pageSize();
    int pages = Math.max(1, (list.size() + size - 1) / size);
    page = Math.min(page, pages - 1);
    for (int row = 0; row < size; row++) {
      int index = page * size + row;
      if (index >= list.size()) break;
      int by = bodyTop + 25 + row * 44;
      var b = button(x + w - 82, by + 2, 68, "解除跳过", () ->
          minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes) Net.request(13, index, local.report().magnet());
          }, Component.literal("重新允许此来源？"),
              Component.literal("仅解除跳过；不会解封或补发不确定物品。再次吸收可能再次报错。"))));
      b.active = canEdit();
    }
    button(x + 10, y + h - 27, 58, "上一页", () -> { page = Math.max(0, page - 1); init(); }).active = page > 0;
    button(x + 72, y + h - 27, 58, "下一页", () -> { page++; init(); }).active = page + 1 < pages;
  }

  private int visibleLines() { return Math.max(1, (bodyBottom - bodyTop - 8) / 10); }
  private void clampScroll() { scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visibleLines()), scroll)); }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    renderBackground(g);
    g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xffb18c4d);
    g.fill(x, y, x + w, y + h, 0xff20242a);
    g.drawString(font, title, x + 10, y + 10, 0xffe8bd68, false);
    g.drawString(font, font.plainSubstrByWidth("本地日志：" + local.folder(), w - 20),
        x + 10, y + 28, 0xfff3ead4, false);
    g.drawString(font, "联机时此处为本地副本；完整服务端报告位置见错误正文。",
        x + 10, y + 39, 0xffc9bda6, false);
    g.fill(x + 8, bodyTop, x + w - 8, bodyBottom, 0xff15191e);
    if (tab == 0) {
      g.enableScissor(x + 9, bodyTop + 2, x + w - 9, bodyBottom - 2);
      for (int i = 0; i < visibleLines() && scroll + i < lines.size(); i++)
        g.drawString(font, lines.get(scroll + i), x + 13, bodyTop + 4 + i * 10, 0xfff3ead4, false);
      g.disableScissor();
      if (lines.size() > visibleLines()) {
        int track = bodyBottom - bodyTop - 8;
        int thumb = Math.max(8, track * visibleLines() / lines.size());
        int offset = (track - thumb) * scroll / Math.max(1, lines.size() - visibleLines());
        g.fill(x + w - 12, bodyTop + 4 + offset, x + w - 10, bodyTop + 4 + offset + thumb, 0xffb18c4d);
      }
    } else renderSkipped(g);
    super.render(g, mx, my, partial);
  }

  private void renderSkipped(GuiGraphics g) {
    var list = data.getList("FaultSkipped", 10);
    String hint = canEdit() ? "解除只允许下次重试，不会补发物品。" : "手持对应磁铁，才能刷新或解除跳过。";
    g.drawString(font, font.plainSubstrByWidth(hint, w - 28), x + 13, bodyTop + 7, 0xffc9bda6, false);
    if (list.isEmpty()) g.drawString(font, "没有已记录的错误来源", x + 13, bodyTop + 29, 0xfff3ead4, false);
    for (int row = 0; row < pageSize(); row++) {
      int index = page * pageSize() + row;
      if (index >= list.size()) break;
      var label = font.split(Component.literal(list.getCompound(index).getString("Label")), w - 108);
      for (int line = 0; line < Math.min(2, label.size()); line++)
        g.drawString(font, label.get(line), x + 13, bodyTop + 27 + row * 44 + line * 11, 0xfff3ead4, false);
    }
    g.drawString(font, "第 " + (page + 1) + " 页" + (data.getInt("FaultSkippedTotal") > 256 ? "（显示前256条）" : ""),
        x + 140, y + h - 21, 0xffc9bda6, false);
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double delta) {
    if (tab == 0) { scroll -= (int) Math.signum(delta) * 3; clampScroll(); return true; }
    return super.mouseScrolled(mx, my, delta);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (tab == 0) {
      if (key == 264 || key == 265 || key == 266 || key == 267 || key == 268 || key == 269) {
        if (key == 264) scroll++;
        if (key == 265) scroll--;
        if (key == 266) scroll -= visibleLines();
        if (key == 267) scroll += visibleLines();
        if (key == 268) scroll = 0;
        if (key == 269) scroll = lines.size();
        clampScroll(); return true;
      }
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override public void onClose() { minecraft.setScreen(parent); }
  @Override public boolean isPauseScreen() { return false; }
}
