package dev.chestmagnet;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/** 静态世界快照。只烘焙 MODEL；动态容器使用金色静态标记，不执行 BE renderer。 */
public final class PreviewMesh implements AutoCloseable, BlockAndTintGetter {
  final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
  List<VertexBuffer> buffers = new ArrayList<>();
  List<BlockPos> queue = List.of();
  int cursor;
  boolean dirty = true;
  TextureTarget target;
  net.minecraft.client.renderer.texture.DynamicTexture white;
  int[] bounds;
  int cut = Integer.MAX_VALUE;

  void add(long[] pos, int[] ids) {
    for (int i = 0; i < Math.min(pos.length, ids.length) && states.size() < 16384; i++)
      states.put(BlockPos.of(pos[i]), Block.stateById(ids[i]));
    dirty = true;
  }

  void clear(int[] body) {
    close();
    states.clear();
    bounds = body;
    dirty = true;
    cursor = 0;
  }

  void bake() {
    if (dirty) {
      for (var b : buffers) b.close();
      buffers.clear();
      queue = new ArrayList<>(states.keySet());
      cursor = 0;
      dirty = false;
    }
    if (cursor >= queue.size()) return;
    long end = System.nanoTime() + 2_000_000;
    var builder = new BufferBuilder(1 << 20);
    builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
    int n = 0;
    var mc = Minecraft.getInstance();
    while (cursor < queue.size() && n++ < 1024 && System.nanoTime() < end) {
      var at = queue.get(cursor++);
      if (at.getY() > cut) continue;
      var state = getBlockState(at);
      if (state.getRenderShape() != RenderShape.MODEL)
        state = Blocks.GOLD_BLOCK.defaultBlockState();
      var pose = new PoseStack();
      pose.translate(at.getX() - bounds[0], at.getY() - bounds[1], at.getZ() - bounds[2]);
      mc.getBlockRenderer()
          .renderBatched(
              state,
              at,
              this,
              pose,
              builder,
              true,
              RandomSource.create(42),
              ModelData.EMPTY,
              RenderType.solid());
    }
    var rendered = builder.end();
    if (rendered.isEmpty()) {
      rendered.release();
      return;
    }
    var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
    buffer.bind();
    buffer.upload(rendered);
    VertexBuffer.unbind();
    buffers.add(buffer);
  }

  void draw(
      net.minecraft.client.gui.GuiGraphics g,
      int x,
      int y,
      int w,
      int h,
      float yaw,
      float pitch,
      float zoom) {
    if (bounds == null) return;
    bake();
    var mc = Minecraft.getInstance();
    if (target == null) target = new TextureTarget(512, 512, true, Minecraft.ON_OSX);
    g.flush();
    target.setClearColor(.08f, .10f, .12f, 1);
    target.clear(Minecraft.ON_OSX);
    target.bindWrite(true);
    RenderSystem.backupProjectionMatrix();
    int oldLightMap=RenderSystem.getShaderTexture(2);
    float oldFogStart = RenderSystem.getShaderFogStart(),
        oldFogEnd = RenderSystem.getShaderFogEnd();
    try {
      float size =
          Math.max(
              bounds[3] - bounds[0] + 1,
              Math.max(bounds[4] - bounds[1] + 1, bounds[5] - bounds[2] + 1));
      var projection =
          new Matrix4f().ortho(-size / zoom, size / zoom, -size / zoom, size / zoom, -2048, 2048);
      RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
      RenderSystem.enableDepthTest();
      RenderSystem.disableCull();
      RenderSystem.setShaderTexture(0, net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
      if (white == null) {
        var image = new com.mojang.blaze3d.platform.NativeImage(1, 1, false);
        image.setPixelRGBA(0, 0, 0xffffffff);
        white = new net.minecraft.client.renderer.texture.DynamicTexture(image);
      }
      RenderSystem.setShaderTexture(2, white.getId());
      RenderSystem.setShaderFogStart(10000);
      RenderSystem.setShaderFogEnd(10001);
      RenderSystem.setShaderColor(1, 1, 1, 1);
      var matrix =
          new Matrix4f()
              .rotateX((float) Math.toRadians(pitch))
              .rotateY((float) Math.toRadians(yaw))
              .translate(
                  -(bounds[3] - bounds[0] + 1) / 2f,
                  -(bounds[4] - bounds[1] + 1) / 2f,
                  -(bounds[5] - bounds[2] + 1) / 2f);
      for (var b : buffers) {
        b.bind();
        b.drawWithShader(matrix, projection, GameRenderer.getRendertypeSolidShader());
      }
      VertexBuffer.unbind();
    } finally {
      RenderSystem.setShaderTexture(2,oldLightMap);
      RenderSystem.setShaderFogStart(oldFogStart);
      RenderSystem.setShaderFogEnd(oldFogEnd);
      RenderSystem.restoreProjectionMatrix();
      mc.getMainRenderTarget().bindWrite(true);
      RenderSystem.disableDepthTest();
      RenderSystem.enableCull();
      RenderSystem.setShaderColor(1, 1, 1, 1);
    }
    RenderSystem.setShader(GameRenderer::getPositionTexShader);
    RenderSystem.setShaderTexture(0, target.getColorTextureId());
    var pose = g.pose().last().pose();
    var bb = Tesselator.getInstance().getBuilder();
    bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
    bb.vertex(pose, x, y + h, 0).uv(0, 0).endVertex();
    bb.vertex(pose, x + w, y + h, 0).uv(1, 0).endVertex();
    bb.vertex(pose, x + w, y, 0).uv(1, 1).endVertex();
    bb.vertex(pose, x, y, 0).uv(0, 1).endVertex();
    BufferUploader.drawWithShader(bb.end());
  }

  public void close() {
    for (var b : buffers) b.close();
    buffers.clear();
    if (white != null) {
      white.close();
      white = null;
    }
    if (target != null) {
      target.destroyBuffers();
      target = null;
    }
  }

  public BlockState getBlockState(BlockPos p) {
    return p.getY() > cut
        ? Blocks.AIR.defaultBlockState()
        : states.getOrDefault(p, Blocks.AIR.defaultBlockState());
  }

  public BlockEntity getBlockEntity(BlockPos p) {
    return null;
  }

  public FluidState getFluidState(BlockPos p) {
    return getBlockState(p).getFluidState();
  }

  public int getHeight() {
    return 4096;
  }

  public int getMinBuildHeight() {
    return -2048;
  }

  public float getShade(Direction d, boolean shade) {
    return !shade
        ? 1
        : switch (d) {
          case DOWN -> .5f;
          case NORTH, SOUTH -> .8f;
          case EAST, WEST -> .65f;
          default -> 1;
        };
  }

  public LevelLightEngine getLightEngine() {
    return null;
  }

  public int getBrightness(LightLayer layer, BlockPos p) {
    return 15;
  }

  public int getRawBrightness(BlockPos p, int n) {
    return 15;
  }

  public int getBlockTint(BlockPos p, ColorResolver r) {
    return 0x91bd59;
  }
}
