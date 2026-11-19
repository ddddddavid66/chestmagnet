package dev.rsqa124;

import dev.chestmagnet.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class TrackingClient {
  private static int stage, wait, elapsed;
  private static boolean done;
  private static final String PROFILE = System.getProperty("chestmagnet.trackQa", "");
  private static final String DIAMONDS = "minecraft:diamond/0/#stored";
  private static final String EMERALDS = "minecraft:emerald/0/#stored";

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (PROFILE.isEmpty() || done || event.phase != TickEvent.Phase.END) return;
    var mc = Minecraft.getInstance();
    var server = mc.getSingleplayerServer();
    if (mc.player == null || server == null) return;
    if (PROFILE.equals("reopen")) {
      if (stage++ == 0) {
        var owner = mc.player.getUUID();
        server.execute(() -> {
          try {
            var p = server.getPlayerList().getPlayer(owner);
            TrackingChecks.require(TrackingChecks.match(p, new ItemStack(Items.DIAMOND_SWORD)), "saved generic sword rule");
            var exact = new ItemStack(Items.DIAMOND_SWORD);
            exact.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 3);
            exact.setDamageValue(5);
            var rules = (java.util.List<?>) TrackingChecks.call(TrackingChecks.TRACK, "rules",
                new Class<?>[]{net.minecraft.server.level.ServerPlayer.class}, p);
            TrackingChecks.require(rules.size() == 2 && TrackingChecks.match(p, exact), "saved generic and history exact rule");
            System.out.println("TRACK_REOPEN_PASS");
          } catch (Exception error) { TrackingChecks.fail(error); }
          mc.execute(mc::stop);
        });
      }
      return;
    }
    try {
      if (TrackingChecks.failure != null || ++elapsed > 650)
        throw new IllegalStateException("stage " + stage + " " + TrackingChecks.failure);
      if (wait > 0) { wait--; return; }
      UUID owner = mc.player.getUUID();
      switch (stage) {
        case 0 -> {
          if (mc.screen != null) return;
          server.execute(() -> TrackingChecks.setup(server.getPlayerList().getPlayer(owner), PROFILE));
          next(20);
        }
        case 1 -> {
          if (!TrackingChecks.prepared || !mc.player.getMainHandItem().is(ChestMagnet.MAGNET.get())) return;
          var creative = new CreativeModeInventoryScreen(mc.player, FeatureFlags.DEFAULT_FLAGS, false);
          mc.setScreen(creative);
          next(6);
        }
        case 2 -> {
          var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
              .get(new net.minecraft.resources.ResourceLocation("chestmagnet:treasure_magnet"));
          TrackingChecks.require(tab != null && tab.getIconItem().getTag().getBoolean("CreativeCover")
              && tab.getDisplayItems().size() == 1 && tab.getDisplayItems().iterator().next().is(ChestMagnet.MAGNET.get()),
              "dedicated creative tab and cover");
          var screen = (CreativeModeInventoryScreen) mc.screen;
          var m = CreativeModeInventoryScreen.class.getDeclaredMethods();
          // 通过参数类型选中创造页，避免测试工具依赖开发映射名。
          for (var method : m) if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == CreativeModeTab.class
              && method.getReturnType() == void.class) {
            method.setAccessible(true);
            method.invoke(screen, tab);
          }
          next(8);
        }
        case 3 -> {
          grab(mc, "tracking-creative.png");
          mc.setScreen(null);
          next(5);
        }
        case 4 -> {
          Net.request(0, 0, Data.id(mc.player.getMainHandItem()));
          next(10);
        }
        case 5 -> {
          if (!(mc.screen instanceof MagnetScreen screen)) return;
          find(screen, "追踪").onPress();
          next(12);
        }
        case 6 -> {
          var screen = (MagnetScreen) mc.screen;
          if (PROFILE.equals("current") && !TrackingJeiCheck.step(mc, screen)) return;
          grab(mc, "tracking-overview.png");
          find(screen, "搜索物品").onPress();
          var box = screen.children().stream().filter(x -> x instanceof EditBox).map(x -> (EditBox)x).findFirst().orElseThrow();
          box.setValue("minecraft:diamond_sword");
          int x = box.getX() + 4;
          int y = (screen.height - field(screen, "imageHeight")) / 2 + field(screen, "contentTop") + 55;
          TrackingChecks.require(screen.mouseClicked(x, y, 1), "picker right click");
          next(10);
        }
        case 7 -> {
          grab(mc, "tracking-selected.png");
          server.execute(() -> TrackingChecks.deliver(server.getPlayerList().getPlayer(owner), PROFILE));
          next(32);
        }
        case 8 -> {
          if (!TrackingChecks.delivered) return;
          verifyToast(mc, TrackingChecks.SWORD + "#stored", 1);
          if (!PROFILE.equals("minimal")) {
            verifyToast(mc, TrackingChecks.PAPER + "#stored", 1);
            verifyToast(mc, TrackingChecks.PAPER + "#destroyed", 2);
            verifyAbsent(mc, "minecraft:iron_ingot/0/#stored");
            verifyAbsent(mc, "minecraft:iron_ingot/0/#destroyed");
          }
          if (PROFILE.equals("current")) { verifyToast(mc, DIAMONDS, 4); verifyToast(mc, EMERALDS, 5); }
          grab(mc, "tracking-toast.png");
          find((MagnetScreen) mc.screen, "最近获取物品").onPress();
          next(12);
        }
        case 9 -> {
          var screen = (MagnetScreen) mc.screen;
          var h = MagnetScreen.class.getDeclaredField("history");
          h.setAccessible(true);
          if (!(h.get(screen) instanceof net.minecraft.nbt.CompoundTag tag) || tag.getInt("Total") == 0) return;
          int x = (screen.width - Math.min(390, screen.width - 12)) / 2 + 17;
          int y = (screen.height - field(screen, "imageHeight")) / 2 + field(screen, "contentTop") + 46;
          TrackingChecks.require(screen.mouseClicked(x, y, 1), "history right click");
          next(10);
        }
        default -> {
          System.out.println("TRACK_CLIENT_PASS " + PROFILE + " scale=" + mc.options.guiScale().get());
          done = true;
          mc.stop();
        }
      }
    } catch (Exception | LinkageError ex) {
      TrackingChecks.fail(ex);
      done = true;
      mc.stop();
    }
  }

  private static void next(int ticks) { stage++; wait = ticks; }
  private static void grab(Minecraft mc, String name) {
    Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), ignore -> {});
  }

  private static Button find(MagnetScreen screen, String name) {
    return screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(name))
        .map(child -> (Button) child).findFirst().orElseThrow();
  }

  private static int field(MagnetScreen screen, String name) throws ReflectiveOperationException {
    Class<?> declaring = MagnetScreen.class;
    java.lang.reflect.Field f = null;
    while (declaring != null && f == null) {
      try { f = declaring.getDeclaredField(name); }
      catch (NoSuchFieldException ignored) { declaring = declaring.getSuperclass(); }
    }
    if (f == null) {
      // 原版字段在正式 JAR 上经过重映射；测试只读面板已知尺寸。
      if (name.equals("imageHeight")) return Math.min(300, screen.height - 8);
      return Math.min(Math.max(224, Math.min(310, screen.width - 112)), screen.width - 12);
    }
    f.setAccessible(true);
    return f.getInt(screen);
  }

  @SuppressWarnings("unchecked")
  private static void verifyAbsent(Minecraft mc, String token) {
    TrackingChecks.require(mc.getToasts().getToast((Class<? extends Toast>) TrackingChecks.TOAST, token) == null,
        "unexpected toast " + token);
  }

  @SuppressWarnings("unchecked")
  private static void verifyToast(Minecraft mc, String token, int expected) throws ReflectiveOperationException {
    var toast = mc.getToasts().getToast((Class<? extends Toast>) TrackingChecks.TOAST, token);
    TrackingChecks.require(toast != null, "missing toast " + token);
    var count = TrackingChecks.TOAST.getDeclaredField("amount");
    count.setAccessible(true);
    TrackingChecks.require(count.getLong(toast) == expected, "toast count " + token + " expected=" + expected + " actual=" + count.getLong(toast));
    System.out.println("TRACK_TOAST_PASS " + token + " " + expected);
  }
}
