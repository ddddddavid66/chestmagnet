package dev.rsqa124;

import dev.chestmagnet.MagnetScreen;
import java.util.Arrays;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.common.Internal;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.bookmarks.BookmarkList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.*;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

final class TrackingJeiCheck {
  private static int phase, at, attempts;

  static boolean step(Minecraft mc, MagnetScreen screen) throws ReflectiveOperationException {
    if (phase == 0) {
      var rt = Internal.getJeiRuntime();
      if (rt == null) return false;
      var stack = new ItemStack(Items.EMERALD);
      rt.getIngredientFilter().setFilterText(stack.getHoverName().getString());
      System.out.println("TRACK_JEI_SEARCH "+stack.getHoverName().getString());
      var overlay = rt.getBookmarkOverlay();
      var field = overlay.getClass().getDeclaredField("bookmarkList");
      field.setAccessible(true);
      var list = (BookmarkList) field.get(overlay);
      var manager = rt.getIngredientManager();
      list.add(IngredientBookmark.create(manager.createTypedIngredient(VanillaTypes.ITEM_STACK,
          new ItemStack(Items.DIAMOND)).orElseThrow(), manager));
      phase++;
      at = 10;
      return false;
    }
    if (at-- > 0) return false;
    switch (phase) {
      case 1, 3 -> {
        if (!click(mc, screen, false, Items.EMERALD)) {
          if (attempts == 12) {
            var rt = Internal.getJeiRuntime();
            System.out.println("TRACK_JEI_VISIBLE " + rt.getIngredientListOverlay().isListDisplayed() + " "
                + rt.getIngredientListOverlay().getVisibleIngredients(VanillaTypes.ITEM_STACK).stream().limit(8).toList());
            Screenshot.grab(mc.gameDirectory, "tracking-jei-probe.png", mc.getMainRenderTarget(), ignore -> {});
          }
          return retry();
        }
      }
      case 2 -> TrackingChecks.require(rule(screen, "minecraft:emerald"), "JEI right result added");
      case 4 -> {
        TrackingChecks.require(!rule(screen, "minecraft:emerald"), "JEI repeated right click removed");
        if (!click(mc, screen, true, Items.DIAMOND)) return retry();
      }
      case 5 -> TrackingChecks.require(rule(screen, "minecraft:diamond"), "JEI left bookmark added");
      case 6 -> {
        if (!click(mc, screen, true, Items.DIAMOND)) return retry();
      }
      default -> {
        TrackingChecks.require(!rule(screen, "minecraft:diamond"), "JEI right click removed tracking, not bookmark");
        Screenshot.grab(mc.gameDirectory, "tracking-jei.png", mc.getMainRenderTarget(), ignore -> {});
        System.out.println("TRACK_JEI_PASS searched item and saved bookmark: add/remove");
        return true;
      }
    }
    phase++;
    attempts = 0;
    at = 6;
    return false;
  }

  private static boolean retry() {
    TrackingChecks.require(++attempts < 40, "JEI overlay item not hittable at phase=" + phase);
    return false;
  }

  private static boolean rule(MagnetScreen screen, String id) throws ReflectiveOperationException {
    var field = MagnetScreen.class.getDeclaredField("tracking");
    field.setAccessible(true);
    var panel = field.get(screen);
    var rules = panel.getClass().getDeclaredField("active");
    rules.setAccessible(true);
    return rules.get(panel).toString().contains("item=" + id + ",");
  }

  private static boolean click(Minecraft mc, MagnetScreen screen, boolean bookmark, Item item)
      throws ReflectiveOperationException {
    var rt = Internal.getJeiRuntime();
    var move = Arrays.stream(mc.mouseHandler.getClass().getDeclaredMethods()).filter(method ->
        (method.getName().equals("m_91561_") || method.getName().equals("onMove"))
            && Arrays.equals(method.getParameterTypes(), new Class<?>[]{long.class, double.class, double.class})).findFirst().orElseThrow();
    move.setAccessible(true);
    var win = mc.getWindow();
    int min = bookmark ? 0 : screen.width / 2;
    int max = bookmark ? screen.width / 2 : screen.width;
    for (int y = 10; y < screen.height - 16; y += 7)
      for (int x = min; x < max; x += 7) {
        move.invoke(mc.mouseHandler, win.getWindow(), x * win.getWidth() / (double) screen.width,
            y * win.getHeight() / (double) screen.height);
        var under = bookmark ? rt.getBookmarkOverlay().getItemStackUnderMouse()
            : rt.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (under == null || !under.is(item)) continue;
        var bus = MinecraftForge.EVENT_BUS;
        TrackingChecks.require(bus.post(new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 1)), "JEI event consumed");
        TrackingChecks.require(bus.post(new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 1)), "JEI release consumed");
        return true;
      }
    return false;
  }
}
