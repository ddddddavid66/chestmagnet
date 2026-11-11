package dev.chestmagnet;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.settings.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** 所有快捷键从同一条物理按下路径派发；原版双手使用事件不再切换两次。 */
@Mod.EventBusSubscriber(modid = ChestMagnet.ID, value = Dist.CLIENT)
public final class ClientEvents {
  public static Net.Snapshot latest;
  static final String CATEGORY = "key.categories.chestmagnet";

  /** Forge 在派发普通快捷键之前就查询 context；不能仅在发包时检查手持物品。 */
  private static final IKeyConflictContext HELD_MAGNET =
      new IKeyConflictContext() {
        @Override
        public boolean isActive() {
          return inputActive();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
          return other == this || other == KeyConflictContext.IN_GAME;
        }
      };

  public static final KeyMapping TOGGLE = mapping("toggle", KeyModifier.NONE, 1);
  public static final KeyMapping SETTINGS = mapping("settings", KeyModifier.SHIFT, 0);
  public static final KeyMapping EXCLUDE = mapping("exclude", KeyModifier.SHIFT, 1);
  public static final KeyMapping STRUCTURE = mapping("structure", KeyModifier.CONTROL, 1);
  private static final Set<InputConstants.Key> CONSUMED = new HashSet<>();
  private static UUID consumedMagnet;
  private static final KeyMapping[] ACTIONS = {TOGGLE, SETTINGS, EXCLUDE, STRUCTURE};

  private static KeyMapping mapping(String name, KeyModifier modifier, int mouse) {
    return new KeyMapping(
        "key.chestmagnet." + name,
        HELD_MAGNET,
        modifier,
        InputConstants.Type.MOUSE,
        mouse,
        CATEGORY);
  }

  private static ItemStack held() {
    var p = Minecraft.getInstance().player;
    if (p == null) return ItemStack.EMPTY;
    return p.getMainHandItem().is(ChestMagnet.MAGNET.get())
        ? p.getMainHandItem()
        : p.getOffhandItem();
  }

  static boolean inputActive() {
    var mc = Minecraft.getInstance();
    return mc.player != null
        && mc.screen == null
        && mc.isWindowActive()
        && mc.player.isAlive()
        && !mc.player.isSpectator()
        && held().is(ChestMagnet.MAGNET.get());
  }

  /** 只清理本模组的状态，不调用全局 releaseAll，避免影响其他模组或原版长按。 */
  static void clearInput() {
    CONSUMED.clear();
    consumedMagnet = null;
    for (var key : ACTIONS) {
      key.setDown(false);
      while (key.consumeClick()) {
        /* 丢弃失去手持前积压的磁铁动作。 */
      }
    }
  }

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    if (e.phase != TickEvent.Phase.START) return;
    boolean active = inputActive();
    boolean changed = consumedMagnet != null && !consumedMagnet.equals(identity(held()));
    if (!active || changed) clearInput();
  }

  @SubscribeEvent
  public static void screenOpening(ScreenEvent.Opening e) {
    clearInput();
  }

  static UUID identity(ItemStack s) {
    var t = s.getTagElement("ChestMagnet");
    return t != null && t.hasUUID("Id") ? t.getUUID("Id") : new UUID(0, 0);
  }

  /** NONE 修饰键也可能匹配组合键，因此显式固定优先级，一次只请求一个动作。 */
  static int action(InputConstants.Key key, boolean air) {
    if (STRUCTURE.isActiveAndMatches(key)) return 20;
    if (EXCLUDE.isActiveAndMatches(key)) return 12;
    if (SETTINGS.isActiveAndMatches(key)) return air ? 0 : -1;
    return TOGGLE.isActiveAndMatches(key) ? 11 : -1;
  }

  private static boolean pressed(InputConstants.Key key) {
    var mc = Minecraft.getInstance();
    var s = held();
    if (!inputActive()) {
      clearInput();
      return false;
    }
    if (CONSUMED.contains(key)) return true;
    int a = action(key, mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.MISS);
    if (a < 0) return false;
    CONSUMED.add(key);
    consumedMagnet = identity(s);
    Net.request(a, 0, identity(s));
    return true;
  }

  @SubscribeEvent
  public static void mouse(InputEvent.MouseButton.Pre e) {
    var key = InputConstants.Type.MOUSE.getOrCreate(e.getButton());
    if (e.getAction() == GLFW.GLFW_RELEASE) {
      boolean consumed = CONSUMED.remove(key);
      if (consumed && inputActive()) e.setCanceled(true);
    } else if (e.getAction() == GLFW.GLFW_PRESS && pressed(key)) e.setCanceled(true);
  }

  @SubscribeEvent
  public static void keyboard(InputEvent.Key e) {
    var key = InputConstants.getKey(e.getKey(), e.getScanCode());
    if (e.getAction() == GLFW.GLFW_RELEASE) CONSUMED.remove(key);
    else if (e.getAction() == GLFW.GLFW_PRESS) pressed(key);
  }

  @SubscribeEvent
  public static void vanillaInteraction(InputEvent.InteractionKeyMappingTriggered e) {
    // 键盘重绑到原版攻击/使用键时，按下已派发，后续原版交互只取消、不再发包。
    if (!inputActive()) {
      clearInput();
      return;
    }
    if (CONSUMED.contains(e.getKeyMapping().getKey())) {
      e.setCanceled(true);
      e.setSwingHand(false);
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
    clearInput();
    latest = null;
    ErrorScreen.clearSession();
  }

  @SubscribeEvent
  public static void commands(RegisterClientCommandsEvent e) {
    e.getDispatcher()
        .register(
            Commands.literal("chestmagnet_error")
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .executes(
                            ctx -> {
                              try {
                                ErrorScreen.open(
                                    UUID.fromString(StringArgumentType.getString(ctx, "id")));
                              } catch (IllegalArgumentException ex) {
                                ctx.getSource().sendFailure(Component.literal("错误报告编号无效"));
                              }
                              return 1;
                            })));
  }

  public static void snapshot(Net.Snapshot s) {
    latest = s;
    var screen = Minecraft.getInstance().screen;
    if (screen instanceof MagnetScreen m) m.update(s);
    if (screen instanceof ErrorScreen error) error.update(s);
  }

  public static void tooltip(List<Component> lines) {
    lines.add(Component.literal("§7启停：").append(TOGGLE.getTranslatedKeyMessage()));
    lines.add(Component.literal("§7配置（对准空气）：").append(SETTINGS.getTranslatedKeyMessage()));
    lines.add(Component.literal("§7排除来源：").append(EXCLUDE.getTranslatedKeyMessage()));
    lines.add(Component.literal("§7结构收取：").append(STRUCTURE.getTranslatedKeyMessage()));
  }

  @Mod.EventBusSubscriber(
      modid = ChestMagnet.ID,
      bus = Mod.EventBusSubscriber.Bus.MOD,
      value = Dist.CLIENT)
  public static class Setup {
    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
      e.register(TOGGLE);
      e.register(SETTINGS);
      e.register(EXCLUDE);
      e.register(STRUCTURE);
    }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent e) {
      e.registerReloadListener(
          (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
              manager -> {
                if (Minecraft.getInstance().screen instanceof StructureScreen s) s.reloadPreview();
              });
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
      e.enqueueWork(() -> MenuScreens.register(Net.MENU.get(), MagnetScreen::new));
    }
  }
}
