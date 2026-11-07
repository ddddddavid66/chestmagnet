package dev.chestmagnet;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = ChestMagnet.ID, value = Dist.CLIENT)
public final class ClientEvents {
  public static Net.Snapshot latest;

  @SubscribeEvent
  public static void air(PlayerInteractEvent.LeftClickEmpty e) {
    var mc = Minecraft.getInstance();
    if (mc.screen != null
        || !e.getEntity().isShiftKeyDown()
        || mc.hitResult == null
        || mc.hitResult.getType() != HitResult.Type.MISS) return;
    if (e.getEntity().getMainHandItem().is(ChestMagnet.MAGNET.get())
        || e.getEntity().getOffhandItem().is(ChestMagnet.MAGNET.get()))
      Net.request(0, 0, new UUID(0, 0));
  }

  public static void snapshot(Net.Snapshot s) {
    latest = s;
    if (Minecraft.getInstance().screen instanceof MagnetScreen screen) screen.update(s);
  }

  @Mod.EventBusSubscriber(
      modid = ChestMagnet.ID,
      bus = Mod.EventBusSubscriber.Bus.MOD,
      value = Dist.CLIENT)
  public static class Setup {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
      e.enqueueWork(() -> MenuScreens.register(Net.MENU.get(), MagnetScreen::new));
    }
  }
}
