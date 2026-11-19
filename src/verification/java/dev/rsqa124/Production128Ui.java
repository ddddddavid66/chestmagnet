package dev.rsqa124;

import dev.chestmagnet.MagnetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class Production128Ui {
  private static int stage, ticks, bossStage, bossTicks;
  private static boolean done;
  public static Boolean tooltipShift;
  private static boolean nativeDetail;
  private static int nativeCalls;
  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (done || e.phase != TickEvent.Phase.END) return;
    var mode = System.getProperty("chestmagnet.qa128", "");
    if (mode.isEmpty()) return;
    var mc = Minecraft.getInstance();
    if (Production128.done) { done = true; mc.stop(); return; }
    if (!mode.equals("ui") || !(mc.screen instanceof MagnetScreen screen)) return;
    try {
      if (bossStage < 3) {
        if (++bossTicks < 30) return;
        if (bossStage == 0) {
          button(screen, "范围").onPress();
          screen.mouseScrolled(mc.getWindow().getGuiScaledWidth()/2.,mc.getWindow().getGuiScaledHeight()/2.,-8);
        } else if (bossStage == 1) {
          button(screen,"打Boss：否").onPress();
        } else {
          Production128.require(button(screen,"打Boss：是")!=null,"server acknowledged Boss setting");
          Screenshot.grab(mc.gameDirectory,"128-boss-range.png",mc.getMainRenderTarget(),ignore->{});
          Production128.LOG.info("BOSS_UI_PASS scale={} server acknowledged toggle",mc.options.guiScale().get());
          button(screen,"最近获取物品").onPress();
        }
        bossStage++;bossTicks=0;return;
      }
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
        if (net.minecraftforge.fml.ModList.get().isLoaded("enchdesc")) {
          button(screen, "分类：饰品").onPress();
          for (int i = 0; i < 2; i++) screen.keyPressed(GLFW.GLFW_KEY_UP, 0, 0);
          screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
          stage++;
        } else succeed(mc);
      }
      if (stage > 0 && ++ticks > 260) throw new IllegalStateException("UI timed out at " + stage);
    } catch (Exception ex) {
      Production128.LOG.error("QA128_UI_FAILED", ex);
      done = true;
      mc.stop();
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void itemTooltip(ItemTooltipEvent event) {
    if (tooltipShift == null || !event.getItemStack().is(Items.ENCHANTED_BOOK)) return;
    nativeCalls++;
    String desc = Component.translatable("enchantment.minecraft.sharpness.desc").getString();
    nativeDetail |= !desc.equals("enchantment.minecraft.sharpness.desc")
        && event.getToolTip().stream().anyMatch(line -> line.getString().contains(desc))
        && event.getToolTip().stream().anyMatch(line ->
            line.getString().contains(Component.translatable("enchantment.level.3").getString()));
  }

  @SubscribeEvent
  public static void render(ScreenEvent.Render.Post event) {
    if ((stage != 5 && stage != 6) || done || !(event.getScreen() instanceof MagnetScreen screen)) return;
    var mc = Minecraft.getInstance();
    try {
      var dataField = MagnetScreen.class.getDeclaredField("history");
      dataField.setAccessible(true);
      var data = (CompoundTag) dataField.get(screen);
      if (data == null || data.getInt("Total") != 1
          || !data.getList("Rows", 10).getCompound(0).getCompound("Display").getString("Item").equals("minecraft:enchanted_book"))
        return;
      var method = MagnetScreen.class.getDeclaredMethod("recentTooltip", GuiGraphics.class, int.class, int.class);
      method.setAccessible(true);
      var content = MagnetScreen.class.getDeclaredField("contentTop");
      content.setAccessible(true);
      int x = (screen.width - Math.min(390, screen.width - 12)) / 2 + 27;
      int y = (screen.height - Math.min(300, screen.height - 8)) / 2 + content.getInt(screen) + 48;
      // 用正式客户端的真实渲染帧，只在 QA 范围内固定鼠标坐标和 Shift 状态。
      tooltipShift = stage == 6;
      method.invoke(screen, event.getGuiGraphics(), x, y);
      event.getGuiGraphics().flush();
      if (stage == 5) {
        Production128.require(nativeCalls == 0 && !nativeDetail, "ordinary hover uses compact history tooltip");
        Screenshot.grab(mc.gameDirectory, "128-repair-normal.png", mc.getMainRenderTarget(), ignore -> {});
        stage++;
        return;
      }
      Production128.require(nativeCalls == 1 && nativeDetail, "Shift must render native enchantment description and level");
      Screenshot.grab(mc.gameDirectory, "128-repair-shift.png", mc.getMainRenderTarget(), ignore -> {});
      Production128.LOG.info("REPAIR128_UI_DETAIL_PASS scale={} nativeCalls={}", mc.options.guiScale().get(), nativeCalls);
      succeed(mc);
    } catch (Exception ex) {
      Production128.LOG.error("QA128_UI_FAILED", ex);
      done = true;
      mc.stop();
    } finally {
      tooltipShift = null;
    }
  }

  private static void succeed(Minecraft mc) {
    Production128.LOG.info("QA128_UI_PASS dropdown keyboard selection, actual spellstones, GUI scale {}", mc.options.guiScale().get());
    done = true;
    mc.stop();
  }
  private static Button button(MagnetScreen screen, String label) {
    return screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c)
        .filter(c -> c.getMessage().getString().startsWith(label)).findFirst().orElseThrow();
  }
}
