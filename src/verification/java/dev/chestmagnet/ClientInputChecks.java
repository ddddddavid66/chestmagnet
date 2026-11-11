package dev.chestmagnet;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Modifier;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyMappingLookup;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** 只存在于 verification 源集；真实窗口按住 Shift 后验证 Forge 的全局修饰键分派。 */
@Mod.EventBusSubscriber(modid = ChestMagnet.ID, value = Dist.CLIENT)
public final class ClientInputChecks {
  private static final String DUMMY = "key.chestmagnet.verification.none";
  private static boolean announced, completed;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END
        || completed
        || !Boolean.getBoolean("chestmagnet.inputCheck")) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null
        || mc.level == null
        || mc.screen != null
        || !mc.isWindowActive()
        || !mc.player.isAlive()
        || mc.player.isSpectator()) return;
    if (!announced) {
      announced = true;
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info("INPUT_CHECK_READY: hold physical Shift in the focused isolated client");
      mc.player.displayClientMessage(Component.literal("按键验证已就绪：请在游戏窗口按住 Shift"), false);
    }
    // 不伪造操作系统输入；由测试人员/授权 sky 操作实际按下 Shift。
    if (!Screen.hasShiftDown() || Screen.hasControlDown() || Screen.hasAltDown()) return;
    completed = true;
    try {
      run();
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info(
              "INPUT_CHECK_PASS: real Forge KeyMappingLookup modifier dispatch,"
                  + " empty/main/offhand");
      mc.player.displayClientMessage(Component.literal("按键验证通过：无磁铁不抢键，主副手磁铁生效"), false);
    } catch (Exception | AssertionError failure) {
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests").error("INPUT_CHECK_FAIL", failure);
      mc.player.displayClientMessage(Component.literal("按键验证失败：查看测试日志"), false);
    }
  }

  public static void run() throws ReflectiveOperationException {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.screen != null || !mc.isWindowActive() || !Screen.hasShiftDown())
      throw new IllegalStateException("需要真实客户端游戏窗口及实际 Shift 按下");
    var player = mc.player;
    var main = player.getMainHandItem();
    var off = player.getOffhandItem();
    var mapping = ClientEvents.SETTINGS;
    var previousKey = mapping.getKey();
    var previousModifier = mapping.getKeyModifier();
    var testKey = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8);
    KeyMapping dummy = null;
    try {
      mapping.setKeyModifierAndCode(KeyModifier.SHIFT, testKey);
      dummy =
          new KeyMapping(
              DUMMY, KeyConflictContext.IN_GAME, KeyModifier.NONE, testKey, ClientEvents.CATEGORY);
      var lookup = new KeyMappingLookup(); // Forge 此类背后是全局静态映射，不能用自建假分派器替代。
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
      check(!mapping.getKeyConflictContext().isActive(), "no magnet context must be inactive");
      var empty = lookup.getAll(testKey);
      check(
          !empty.contains(mapping) && empty.contains(dummy),
          "no magnet must preserve unmodified mapping");
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChestMagnet.MAGNET.get()));
      var mainMatches = lookup.getAll(testKey);
      check(
          mapping.getKeyConflictContext().isActive()
              && mainMatches.contains(mapping)
              && !mainMatches.contains(dummy),
          "mainhand must activate modifier mapping");
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ChestMagnet.MAGNET.get()));
      var offMatches = lookup.getAll(testKey);
      check(
          mapping.getKeyConflictContext().isActive()
              && offMatches.contains(mapping)
              && !offMatches.contains(dummy),
          "offhand must activate modifier mapping");
      // 不派发 click/setDown 或磁铁网络动作，只在本次同步检查内暂换客户端显示的装备。
    } finally {
      player.setItemInHand(InteractionHand.MAIN_HAND, main);
      player.setItemInHand(InteractionHand.OFF_HAND, off);
      mapping.setKeyModifierAndCode(previousModifier, previousKey);
      if (dummy != null) removeTemporary(dummy);
    }
  }

  private static void check(boolean result, String reason) {
    if (!result) throw new AssertionError(reason);
  }

  private static void removeTemporary(KeyMapping dummy) throws ReflectiveOperationException {
    new KeyMappingLookup().remove(dummy);
    // 构造器还会登记 ALL；按本次唯一实例删除，防止 resetMapping 将临时绑定重新加入。
    for (var field : KeyMapping.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType()))
        continue;
      field.setAccessible(true);
      if (field.get(null) instanceof Map<?, ?> registry && registry.get(DUMMY) == dummy)
        registry.remove(DUMMY);
    }
  }
}
