package dev.rsqa124;

import dev.chestmagnet.MagnetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class Production128Ui {
  private static int stage, ticks;
  private static boolean done;
  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (done || e.phase != TickEvent.Phase.END) return;
    var mode = System.getProperty("chestmagnet.qa128", "");
    if (mode.isEmpty()) return;
    var mc = Minecraft.getInstance();
    if (Production128.done) { done = true; mc.stop(); return; }
    if (!mode.equals("ui") || !(mc.screen instanceof MagnetScreen screen)) return;
    try {
      var historyField = MagnetScreen.class.getDeclaredField("history");
      historyField.setAccessible(true);
      var data = (CompoundTag) historyField.get(screen);
      if (stage == 0 && ++ticks >= 60) {
        button(screen, "最近获取物品").onPress();
        stage++;
      } else if (stage == 1 && data != null && data.getInt("Total") >= 6) {
        button(screen, "分类：全部").onPress();
        stage++;
      } else if (stage == 2 && ++ticks >= 68) {
        Screenshot.grab(mc.gameDirectory, "128-category.png", mc.getMainRenderTarget(), ignore -> {});
        for (int i = 0; i < 4; i++) screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        stage++;
      } else if (stage == 3 && data != null) {
        var rows = data.getList("Rows", 10);
        var names = rows.stream().map(t -> ((CompoundTag)t).getCompound("Display").getString("Item")).toList();
        Production128.require(names.contains("enigmaticlegacy:ocean_stone")
            && names.contains("enigmaticaddons:revival_leaf") && names.contains("minecraft:paper")
            && names.contains("minecraft:feather") && names.size() == 4,
            "real spellstones, unknown Curios slot tag and capability-only accessory: " + names);
        stage++;
      } else if (stage == 4) {
        Screenshot.grab(mc.gameDirectory, "128-accessories.png", mc.getMainRenderTarget(), ignore -> {});
        Production128.LOG.info("QA128_UI_PASS dropdown keyboard selection, actual spellstones, GUI scale {}", mc.options.guiScale().get());
        done = true;
        mc.stop();
      }
      if (stage > 0 && ++ticks > 260) throw new IllegalStateException("UI timed out at " + stage);
    } catch (Exception ex) {
      Production128.LOG.error("QA128_UI_FAILED", ex);
      done = true;
      mc.stop();
    }
  }
  private static Button button(MagnetScreen screen, String label) {
    return screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c)
        .filter(c -> c.getMessage().getString().startsWith(label)).findFirst().orElseThrow();
  }
}
