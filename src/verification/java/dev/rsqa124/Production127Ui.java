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

@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class Production127Ui {
  static volatile boolean finished;
  private static int stage, ticks;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (!Boolean.getBoolean("chestmagnet.qa127") || finished
        || event.phase != TickEvent.Phase.END) return;
    var mc = Minecraft.getInstance();
    if (!(mc.screen instanceof MagnetScreen screen)) return;
    try {
      ticks++;
      var field = MagnetScreen.class.getDeclaredField("history");
      field.setAccessible(true);
      var data = (CompoundTag) field.get(screen);
      if (stage == 0) {
        button(screen, "最近获取物品").onPress();
        stage++;
      } else if (stage == 1 && data != null && data.getInt("Pages") > 1) {
        screenshot(mc, "127-recent");
        button(screen, ">").onPress();
        stage++;
      } else if (stage == 2 && data != null && data.getInt("Page") == 1) {
        button(screen, "分类：").onPress();
        button(screen, "分类：").onPress();
        stage++;
      } else if (stage == 3 && data != null) {
        var rows = data.getList("Rows", 10);
        if (rows.size() != 1 || rows.getCompound(0).getCompound("Display").getList("Enchants", 10).size() != 1)
          throw new IllegalStateException("enchanted book filter failed: " + data);
        screenshot(mc, "127-books");
        button(screen, "范围").onPress();
        stage++;
      } else if (stage == 4) {
        for (int i = 0; i < 12; i++) screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, -1);
        button(screen, "上锁战利品");
        button(screen, "展示框物品");
        stage++;
      } else if (stage == 5) {
        screenshot(mc, "127-sources");
        NearbyProductionChecks.LOG.info("QA127_RECENT_UI_PASS actual paged data, enchanted book and source controls, GUI scale {}", mc.options.guiScale().get());
        finished = true;
        screen.onClose();
      }
      if (ticks > 160) throw new IllegalStateException("UI timed out at stage " + stage);
    } catch (Exception ex) {
      NearbyProductionChecks.LOG.error("QA127_UI_FAILED", ex);
      finished = true;
    }
  }

  private static Button button(MagnetScreen screen, String label) {
    return screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c)
        .filter(c -> c.getMessage().getString().startsWith(label)).findFirst().orElseThrow();
  }

  private static void screenshot(Minecraft mc, String name) {
    mc.getToasts().clear();
    Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {});
  }
}
