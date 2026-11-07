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

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (e.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.smoke")) return;
    var mc = Minecraft.getInstance();
    if (Boolean.getBoolean("chestmagnet.renderSmoke")) {
      renderTicks++;
      if (!fixtureLoaded && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
        try {
          var d =
              net.minecraft.nbt.NbtIo.readCompressed(
                  new java.io.File(mc.gameDirectory, "structure-preview.nbt"));
          var screen = new StructureScreen(d);
          mc.setScreen(screen);
          var tags = d.getList("BlockStates", 10);
          int[] ids = new int[tags.size()];
          for (int i = 0; i < ids.length; i++)
            ids[i] =
                net.minecraft.world.level.block.Block.getId(
                    net.minecraft.nbt.NbtUtils.readBlockState(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                        tags.getCompound(i)));
          screen.mesh.add(d.getLongArray("Positions"), ids);
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
        screen.yaw = 85;
        screen.pitch = 35;
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
      s.yaw = 75;
      s.pitch = 45;
    }
    if (ticks == 330) {
      mc.stop();
    }
  }
}
