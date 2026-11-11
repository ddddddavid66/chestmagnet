package dev.chestmagnet;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "chestmagnet", value = Dist.CLIENT)
public class ClientSmoke {
  static int ticks;
  static int renderTicks;
  static boolean fixtureLoaded;
  static MagnetScreen settingsFixture;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (e.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.smoke")) return;
    var mc = Minecraft.getInstance();
    if (Boolean.getBoolean("chestmagnet.sourceSmoke")) {
      renderTicks++;
      if (!fixtureLoaded && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
        if (Boolean.getBoolean("chestmagnet.errorSmoke")) {
          try {
            var id=UUID.randomUUID(); var report=new Faults.Report(id,UUID.randomUUID(),"宝箱磁铁1.2.2\n服务端日志：logs/chestmagnet/errors/测试/示例.log\n来源：Tetra锻造板条箱 · minecraft:overworld · 100,20,200\n阶段：破坏许可\n措施：来源已长期跳过，下次开启可继续其他来源。\njava.lang.IllegalStateException: 测试报告（不是真实游戏故障）\n"+"    at dev.chestmagnet.SourceExtras.checkBreakPermission(SourceExtras.java)\n".repeat(24));
            var type=Class.forName("dev.chestmagnet.ErrorScreen$LocalReport");
            var localCtor=type.getDeclaredConstructors()[0]; localCtor.setAccessible(true);
            var local=localCtor.newInstance(report,mc.gameDirectory.toPath().resolve("logs/chestmagnet/errors"),"");
            var ctor=ErrorScreen.class.getDeclaredConstructor(type,net.minecraft.client.gui.screens.Screen.class);ctor.setAccessible(true);
            mc.setScreen((net.minecraft.client.gui.screens.Screen)ctor.newInstance(local,mc.screen)); fixtureLoaded=true;renderTicks=0;return;
          } catch(Exception ex){throw new RuntimeException(ex);}
        }
        var id = UUID.randomUUID();
        var data = new CompoundTag();
        data.putUUID("Id", id);
        ClientEvents.latest = new Net.Snapshot(data, "设置界面夹具 · 不是真实游戏任务", 64);
        var inv = new net.minecraft.world.entity.player.Inventory(null);
        var screen =
            new MagnetScreen(
                new MagnetMenu(0, inv, false, id),
                inv,
                net.minecraft.network.chat.Component.literal("宝箱磁铁"));
        settingsFixture = screen;
        mc.setScreen(
            new net.minecraft.client.gui.screens.Screen(
                net.minecraft.network.chat.Component.literal("source fixture")) {
              @Override
              protected void init() {
                screen.init(mc, width, height);
              }

              @Override
              public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float dt) {
                screen.render(g, x, y, dt);
              }
            });
        fixtureLoaded = true;
        renderTicks = 0;
      }
      if (fixtureLoaded && (renderTicks == 80 || renderTicks == 130))
        Screenshot.grab(
            mc.gameDirectory,
            mc.getMainRenderTarget(),
            c ->
                org.slf4j.LoggerFactory.getLogger("chestmagnet")
                    .info("SOURCE_UI {}", c.getString()));
      if (fixtureLoaded && renderTicks == 100) {
        if (settingsFixture != null) {
          try {
            var field = MagnetScreen.class.getDeclaredField("tab");
            field.setAccessible(true);
            field.setInt(settingsFixture, 1);
          } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        }
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
      }
      if (fixtureLoaded && renderTicks == 160) mc.stop();
      return;
    }
    if (Boolean.getBoolean("chestmagnet.renderSmoke")) {
      renderTicks++;
      if (!fixtureLoaded && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
        try {
          mc.options.guiScale().set(3);
          mc.resizeDisplay();
          var d = mapFixture();
          var screen = new StructureScreen(d);
          mc.setScreen(screen);
          fixtureLoaded = true;
          renderTicks = 0;
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
      if (fixtureLoaded && (renderTicks == 100 || renderTicks == 140))
        Screenshot.grab(
            mc.gameDirectory,
            mc.getMainRenderTarget(),
            c ->
                org.slf4j.LoggerFactory.getLogger("chestmagnet")
                    .info("RENDER_FIXTURE {}", c.getString()));
      if (fixtureLoaded && renderTicks == 115 && mc.screen instanceof StructureScreen screen) {
        mc.options.guiScale().set(2);
        mc.resizeDisplay();
      }
      if (fixtureLoaded && renderTicks == 180) mc.stop();
      return;
    }
    if (mc.player == null || mc.getSingleplayerServer() == null) return;
    ticks++;
    if (ticks == 60)
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var server = mc.getSingleplayerServer();
                var p = server.getPlayerList().getPlayer(mc.player.getUUID());
                var level =
                    server.getLevel(
                        ResourceKey.create(
                            Registries.DIMENSION, new ResourceLocation("chestmagnet", "natural")));
                var holder =
                    level
                        .registryAccess()
                        .registryOrThrow(Registries.STRUCTURE)
                        .getHolderOrThrow(
                            ResourceKey.create(
                                Registries.STRUCTURE,
                                new ResourceLocation("minecraft", "jungle_pyramid")));
                var located =
                    level
                        .getChunkSource()
                        .getGenerator()
                        .findNearestMapStructure(
                            level, HolderSet.direct(holder), new BlockPos(0, 64, 0), 100, false);
                var cp = new ChunkPos(located.getFirst());
                level.getChunk(cp.x, cp.z);
                var body =
                    StructureJobs.bounds(
                        level.getChunk(cp.x, cp.z).getStartForStructure(holder.value()));
                p.teleportTo(level, body.minX() + 3, body.maxY() + 2, body.minZ() + 3, 0, 0);
                var m = new ItemStack(ChestMagnet.MAGNET.get());
                Data.id(m);
                Data.tag(m).putBoolean("Enabled", false);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, m);
                var result = new CompoundTag();
                result.putInt("Version", 1);
                result.putString("State", "FOUND");
                result.putString("Dimension", "chestmagnet:natural");
                result.putString("Structure", "minecraft:jungle_pyramid");
                result.putInt("StartX", cp.x);
                result.putInt("StartZ", cp.z);
                result.putUUID("Revision", UUID.randomUUID());
                p.getPersistentData().put("ExplorerStructureSnapshotV1", result);
              });
    if (ticks == 120)
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                StructureJobs.request(p, new Net.Request(20, 0, new UUID(0, 0)));
              });
    if (ticks == 250 || ticks == 290)
      Screenshot.grab(
          mc.gameDirectory,
          mc.getMainRenderTarget(),
          c ->
              org.slf4j.LoggerFactory.getLogger("chestmagnet")
                  .info("STRUCTURE_UI {}", c.getString()));
    if (ticks == 265 && mc.screen instanceof StructureScreen s) {
      s.zoom = 2;
    }
    if (ticks == 330) {
      mc.stop();
    }
  }

  /** 只验证客户端布局、颜色和缩放；这是显式构造的截图夹具，不冒充自然建筑。 */
  private static CompoundTag mapFixture() {
    var data = new CompoundTag();
    data.putUUID("Nonce", UUID.randomUUID());
    data.putString("Structure", "chestmagnet:二维截图夹具（非实景）");
    data.putString("Status", "客户端渲染夹具；真实采样另由GameTest与自然世界客户端验证");
    data.putIntArray("Body", new int[] {-48, -20, -48, 48, 20, 48});
    data.putIntArray("Region", new int[] {-64, -64, -64, 64, 319, 64});
    data.putBoolean("Map2D", true);
    data.putInt("MapRevision", 0);
    data.putInt("MapX", -64);
    data.putInt("MapZ", -64);
    data.putInt("MapY", 0);
    data.putInt("MapSize", 128);
    data.putBoolean("MapDone", true);
    int[] indices = new int[128 * 128], colors = new int[indices.length];
    byte[] flags = new byte[indices.length];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i;
      int x = i % 128 - 64, z = i / 128 - 64;
      boolean inside = Math.abs(x) < 46 && Math.abs(z) < 46;
      boolean room = (Math.abs(x) < 12 || Math.abs(z) < 7)
          || (Math.abs(x) > 20 && Math.abs(x) < 39 && Math.abs(z) > 17 && Math.abs(z) < 36);
      flags[i] = (byte) (!inside ? 0 : room ? PlanPreview.FLOOR | 16 : PlanPreview.WALL);
      colors[i] = room ? 0xff995b44 : 0xff292d32;
    }
    data.putIntArray("MapIndices", indices);
    data.putIntArray("MapColors", colors);
    data.putByteArray("MapFlags", flags);
    data.putIntArray("MapPieces", new int[] {-46, -46, 46, 46, -12, -46, 12, 46, -46, -7, 46, 7});
    data.putInt("MapPieceTotal", 12);
    data.putLongArray("Containers", new long[] {new BlockPos(-5, -1, 12).asLong(), new BlockPos(28, -1, 27).asLong()});
    data.putInt("Total", 49);
    data.putInt("Done", 12);
    data.putInt("Ready", 4);
    data.putInt("Waiting", 33);
    data.putInt("Found", 8);
    data.putInt("Processed", 256);
    return data;
  }
}
