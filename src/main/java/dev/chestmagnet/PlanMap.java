package dev.chestmagnet;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** 二维现场快照绘制器。未知、空气、墙体是不同状态，不渲染任何方块实体。 */
final class PlanMap implements AutoCloseable {
  static final int SIZE = PlanPreview.SIZE;
  int revision = -1, x, z, y, known;
  int[] pieces = new int[0];
  final int[] colors = new int[SIZE * SIZE];
  final byte[] flags = new byte[SIZE * SIZE];
  boolean dirty = true, done, simplified;
  private DynamicTexture texture;
  private ResourceLocation textureId;
  private double drawX, drawY, scale;
  private int areaX, areaY, areaW, areaH;

  void receive(CompoundTag tag) {
    if (!tag.contains("MapRevision")) return;
    int incoming = tag.getInt("MapRevision");
    if (incoming < revision) return;
    if (incoming > revision) clear(incoming);
    if (tag.getBoolean("Map2D")) {
      x = tag.getInt("MapX");
      z = tag.getInt("MapZ");
      y = tag.getInt("MapY");
      done = tag.getBoolean("MapDone");
      simplified = tag.getBoolean("MapLayoutSimplified");
    }
    if (tag.contains("MapPieces")) {
      int total = tag.getInt("MapPieceTotal"), offset = tag.getInt("MapPieceOffset");
      int[] values = tag.getIntArray("MapPieces");
      if (total >= 0 && total <= 131072 && offset >= 0 && offset + values.length <= total) {
        if (pieces.length != total) pieces = new int[total];
        System.arraycopy(values, 0, pieces, offset, values.length);
      }
    }
    int[] indices = tag.getIntArray("MapIndices"), values = tag.getIntArray("MapColors");
    byte[] kinds = tag.getByteArray("MapFlags");
    for (int i = 0; i < Math.min(indices.length, Math.min(values.length, kinds.length)); i++) {
      int at = indices[i];
      if (at < 0 || at >= colors.length) continue;
      if (flags[at] == 0 && kinds[i] != 0) known++;
      colors[at] = values[i];
      flags[at] = kinds[i];
      dirty = true;
    }
  }

  void clear(int version) {
    revision = version;
    known = 0;
    done = false;
    pieces = new int[0];
    Arrays.fill(colors, 0);
    Arrays.fill(flags, (byte) 0);
    dirty = true;
  }

  int expect(int centerX, int centerZ, int height) {
    int next = Math.max(0, revision + 1);
    clear(next);
    x = centerX - SIZE / 2;
    z = centerZ - SIZE / 2;
    y = height;
    return next;
  }

  private void upload() {
    if (texture == null) {
      texture = new DynamicTexture(new NativeImage(SIZE, SIZE, false));
      texture.setFilter(false, false);
      textureId = Minecraft.getInstance().getTextureManager().register("chestmagnet_plan", texture);
      dirty = true;
    }
    if (!dirty) return;
    NativeImage pixels = texture.getPixels();
    if (pixels == null) return;
    for (int i = 0; i < colors.length; i++) {
      int color = colors[i];
      if (flags[i] == 0) color = ((i % SIZE + i / SIZE) / 4 % 2 == 0) ? 0xff343d49 : 0xff252c36;
      // NativeImage使用ABGR；网络中的地图色使用常规ARGB。
      int abgr = color & 0xff00ff00 | (color & 255) << 16 | color >> 16 & 255;
      pixels.setPixelRGBA(i % SIZE, i / SIZE, abgr);
    }
    texture.upload();
    dirty = false;
  }

  void draw(GuiGraphics g, int left, int top, int width, int height,
      float zoom, double panX, double panZ, long[] markers) {
    upload();
    areaX = left; areaY = top; areaW = width; areaH = height;
    scale = Math.max(0.1, Math.min(width, height) / (double) SIZE * zoom);
    drawX = left + (width - SIZE * scale) / 2 + panX;
    drawY = top + (height - SIZE * scale) / 2 + panZ;
    g.fill(left, top, left + width, top + height, 0xff11171e);
    g.enableScissor(left, top, left + width, top + height);
    g.blit(textureId, (int) drawX, (int) drawY, (int) Math.ceil(SIZE * scale),
        (int) Math.ceil(SIZE * scale), 0, 0, SIZE, SIZE, SIZE, SIZE);
    for (int i = 0; i + 3 < pieces.length; i += 4)
      outline(g, pieces[i], pieces[i + 1], pieces[i + 2], pieces[i + 3], 0xff9d855b);
    for (long encoded : markers) {
      BlockPos pos = BlockPos.of(encoded);
      if (Math.abs(pos.getY() + 1 - y) > PlanPreview.DEPTH) continue;
      int px = pixelX(pos.getX()), pz = pixelZ(pos.getZ());
      g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xffffce63);
    }
    var player = Minecraft.getInstance().player;
    if (player != null) {
      int px = pixelX(player.getBlockX()), pz = pixelZ(player.getBlockZ());
      g.fill(px - 3, pz, px + 4, pz + 1, 0xff6ee4e6);
      g.fill(px, pz - 3, px + 1, pz + 4, 0xff6ee4e6);
    }
    g.disableScissor();
    g.renderOutline(left, top, width, height, 0xff776345);
  }

  void overview(GuiGraphics g, int left, int top, int width, int height, int[] body) {
    if (body.length != 6) return;
    g.fill(left, top, left + width, top + height, 0xff151b22);
    int spanX = Math.max(1, body[3] - body[0] + 1), spanZ = Math.max(1, body[5] - body[2] + 1);
    double unit = Math.min((width - 8) / (double) spanX, (height - 8) / (double) spanZ);
    double ox = left + (width - spanX * unit) / 2, oz = top + (height - spanZ * unit) / 2;
    g.enableScissor(left, top, left + width, top + height);
    for (int i = 0; i + 3 < pieces.length; i += 4) {
      int a = (int) (ox + (pieces[i] - body[0]) * unit);
      int b = (int) (oz + (pieces[i + 1] - body[2]) * unit);
      int c = Math.max(a + 1, (int) Math.ceil(ox + (pieces[i + 2] + 1 - body[0]) * unit));
      int d = Math.max(b + 1, (int) Math.ceil(oz + (pieces[i + 3] + 1 - body[2]) * unit));
      g.fill(a, b, c, d, 0xff39382e);
      g.renderOutline(a, b, c - a, d - b, 0xffb69a61);
    }
    int vx = (int) (ox + (x - body[0]) * unit), vz = (int) (oz + (z - body[2]) * unit);
    g.renderOutline(vx, vz, Math.max(1, (int) (SIZE * unit)), Math.max(1, (int) (SIZE * unit)), 0xff6ee4e6);
    g.disableScissor();
    g.renderOutline(left, top, width, height, 0xff776345);
  }

  private void outline(GuiGraphics g, int minX, int minZ, int maxX, int maxZ, int color) {
    int a = pixelX(minX), b = pixelZ(minZ), c = pixelX(maxX + 1), d = pixelZ(maxZ + 1);
    g.renderOutline(a, b, Math.max(1, c - a), Math.max(1, d - b), color);
  }

  private int pixelX(int worldX) { return (int) Math.floor(drawX + (worldX - x) * scale); }
  private int pixelZ(int worldZ) { return (int) Math.floor(drawY + (worldZ - z) * scale); }
  int shiftX(double pixels) { return (int) Math.round(pixels / scale); }
  int shiftZ(double pixels) { return (int) Math.round(pixels / scale); }

  boolean contains(double mouseX, double mouseY) {
    return mouseX >= areaX && mouseX < areaX + areaW && mouseY >= areaY && mouseY < areaY + areaH;
  }

  String at(double mouseX, double mouseY) {
    int px = (int) Math.floor((mouseX - drawX) / scale), pz = (int) Math.floor((mouseY - drawY) / scale);
    if (px < 0 || px >= SIZE || pz < 0 || pz >= SIZE) return "视窗外 · 拖动查看";
    int kind = flags[px + pz * SIZE] & 15, depth = (flags[px + pz * SIZE] & 255) >> 4;
    String name = switch (kind) {
      case PlanPreview.WALL -> "墙体／实心";
      case PlanPreview.FLOOR -> "地板 Y:" + (y - depth);
      case PlanPreview.LIQUID -> "液体 Y:" + (y - depth);
      case PlanPreview.OPEN -> "空气／深空";
      default -> "未知：区块未加载或正在采样";
    };
    return "X:" + (x + px) + " Z:" + (z + pz) + " · " + name;
  }

  @Override
  public void close() {
    if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
    texture = null;
    textureId = null;
    dirty = true;
  }
}
