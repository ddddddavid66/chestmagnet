package dev.chestmagnet;

import mezz.jei.api.*;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/** 由 JEI 客户端发现；通用和专用服务端入口不引用此类。 */
@JeiPlugin
public final class MagnetJei implements IModPlugin {
  private IJeiRuntime runtime;
  private MagnetScreen pressed;

  public MagnetJei() {
    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::press);
    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::release);
    MinecraftForge.EVENT_BUS.addListener(this::opening);
  }

  @Override public ResourceLocation getPluginUid() { return new ResourceLocation(ChestMagnet.ID, "tracking"); }
  @Override public void onRuntimeAvailable(IJeiRuntime next) { runtime = next; }
  @Override public void onRuntimeUnavailable() { runtime = null; pressed = null; }

  private void press(ScreenEvent.MouseButtonPressed.Pre event) {
    if (event.getButton() != 1) return;
    pressed = null;
    if (runtime == null || !(event.getScreen() instanceof MagnetScreen screen)
        || !screen.trackingOutside(event.getMouseX(), event.getMouseY())) return;
    ItemStack stack = runtime.getBookmarkOverlay().getItemStackUnderMouse();
    if (stack == null || stack.isEmpty()) {
      if (!runtime.getIngredientListOverlay().isListDisplayed()) return;
      stack = runtime.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
    }
    if (stack != null && screen.selectTracked(stack)) {
      pressed = screen;
      event.setCanceled(true);
    }
  }

  private void release(ScreenEvent.MouseButtonReleased.Pre event) {
    if (event.getButton() == 1 && pressed != null) {
      if (event.getScreen() == pressed) event.setCanceled(true);
      pressed = null;
    }
  }

  private void opening(ScreenEvent.Opening event) { pressed = null; }
}
