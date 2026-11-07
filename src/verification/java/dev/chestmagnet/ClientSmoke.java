package dev.chestmagnet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

@Mod.EventBusSubscriber(modid = "chestmagnet", value = Dist.CLIENT)
public class ClientSmoke {
  static int ticks;
  static boolean opened;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (e.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.smoke")) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.getSingleplayerServer() == null) return;
    ticks++;
    if (ticks == 60) {
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                var stack = new ItemStack(ChestMagnet.MAGNET.get());
                Data.id(stack);
                Data.toggle(stack, "test:barrel", "木桶 · minecraft:overworld · 128,64,-32");
                Data.toggle(stack, "test:cart", "运输矿车 · minecraft:overworld · 132,64,-28");
                Data.tag(stack).putBoolean("Enabled", false);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
                var id = Data.id(stack);
                Data.toggle(stack, "test:barrel", "木桶 · minecraft:overworld · 128,64,-32");
                Data.toggle(stack, "test:cart", "运输矿车 · minecraft:overworld · 132,64,-28");
                NetworkHooks.openScreen(
                    p,
                    new SimpleMenuProvider(
                        (n, inv, player) -> new MagnetMenu(n, inv, false, id),
                        Component.literal("宝箱磁铁")),
                    b -> {
                      b.writeBoolean(false);
                      b.writeUUID(id);
                    });
                Net.sync(p, stack, "测试预览 · 磁铁已暂停");
              });
    }
    if (ticks == 120 || ticks == 160)
      Screenshot.grab(
          mc.gameDirectory,
          mc.getMainRenderTarget(),
          c ->
              org.slf4j.LoggerFactory.getLogger("chestmagnet")
                  .info("UI capture {}", c.getString()));
    if (ticks == 130 && mc.screen instanceof MagnetScreen screen) {
      try {
        var f = MagnetScreen.class.getDeclaredField("tab");
        f.setAccessible(true);
        f.setInt(screen, 1);
        screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
    if (ticks == 190) {
      mc.player.closeContainer();
      mc.stop();
    }
  }
}
