package dev.chestmagnet.qa;

import dev.chestmagnet.ChestMagnet;
import dev.chestmagnet.ClientEvents;
import dev.chestmagnet.Data;
import dev.chestmagnet.MagnetScreen;
import dev.chestmagnet.Net;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "chestmagnetqa", value = Dist.CLIENT)
final class ClientProductionQa {
  private static final boolean RUN = Boolean.getBoolean("chestmagnet.qaClient");
  private static final UUID MAGNET = UUID.fromString("660072dd-697d-43e4-b83f-68e09ce6ea7f");
  private static int stage, ticks, stepAge;

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    if (!RUN || stage < 0) return;
    var mc = Minecraft.getInstance();
    var server = mc.getSingleplayerServer();
    if (mc.player == null || server == null || ticks++ < 50) return;
    try {
      if (++stepAge > 600) throw new IllegalStateException("expected client milestone stage=" + stage + ", screen=" + mc.screen);
      switch (stage) {
        case 0 -> {
          if (mc.screen != null) return;
          if (ClientEvents.TOGGLE.getKeyConflictContext().isActive()) throw new AssertionError("no magnet: must be inactive");
          server.execute(() -> {
            var p = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (p == null) return;
            var stack = new ItemStack(ChestMagnet.MAGNET.get());
            Data.edit(stack, data -> data.putUUID("Id", MAGNET));
            p.getInventory().setItem(0, stack);
            p.getInventory().selected = 0;
            p.teleportTo(p.getX(), p.getY() + 1, p.getZ());
            p.setXRot(-90);
            var barrel = NeoForgeProductionQa.container(p.serverLevel(), p.blockPosition().east(2),
                net.minecraft.world.level.block.Blocks.BARREL);
            barrel.setItem(0, new ItemStack(net.minecraft.world.item.Items.EMERALD, 4));
            p.inventoryMenu.broadcastChanges();
          });
          next();
        }
        case 1 -> {
          if (!mc.player.getMainHandItem().is(ChestMagnet.MAGNET.get()) || stepAge < 25
              || mc.player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) != 4) return;
          if (mc.isWindowActive() && !ClientEvents.TOGGLE.getKeyConflictContext().isActive())
            throw new AssertionError("holding magnet: active while focused");
          mc.player.setXRot(-90);
          next();
        }
        case 2 -> {
          if (stepAge < 12) return;
          Net.request(0, 0, MAGNET);
          next();
        }
        case 3 -> {
          if (!(mc.screen instanceof MagnetScreen screen) || ClientEvents.latest == null) return;
          if (ClientEvents.TOGGLE.getKeyConflictContext().isActive()) throw new AssertionError("open GUI: inactive");
          var recent = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
              .filter(button -> button.getMessage().getString().contains("最近获取物品")).findFirst().orElseThrow();
          recent.onPress();
          next();
        }
        case 4 -> {
          if (stepAge < 30) return;
          if (!(mc.screen instanceof MagnetScreen screen)) throw new AssertionError("history remains in native menu");
          var field = MagnetScreen.class.getDeclaredField("history");
          field.setAccessible(true);
          var history = (net.minecraft.nbt.CompoundTag) field.get(screen);
          var rows = history.getList("Rows", net.minecraft.nbt.Tag.TAG_COMPOUND);
          if (rows.isEmpty()) return;
          var row = rows.getCompound(0);
          if (!row.getCompound("Display").getString("Item").equals("minecraft:emerald") || row.getLong("Count") != 4)
            throw new AssertionError("server history payload does not match collected items");
          NeoForgeProductionQa.LOG.info("QA_NEOFORGE_CLIENT_PASS live collection=4, play payload, settings, history and key context");
          stage = -1;
          mc.stop();
        }
      }
    } catch (Throwable ex) {
      stage = -1;
      NeoForgeProductionQa.LOG.error("QA_NEOFORGE_CLIENT_FAILURE", ex);
      mc.stop();
    }
  }

  private static void next() { stage++; stepAge = 0; }
}
