package dev.chestmagnet;

import com.refinedmods.refinedstorage.apiimpl.network.node.WirelessTransmitterNetworkNode;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraftforge.fml.ModList;

/** RSInfinityBooster +32/+41 的只读覆盖适配；不创建无线会话、不打开界面或扣开启费。 */
final class BoosterCompat {
  private static final String MOD_ID = "rsinfinitybooster";

  private record Hook(Method dimensionCard, Throwable failure) {}

  private static class LoadedHook {
    private static final Hook VALUE = findHook();
  }

  private static Hook findHook() {
    try {
      var type =
          Class.forName(
              "uk.co.hexeption.rsinfinitybooster.utils.CardUtil",
              false,
              BoosterCompat.class.getClassLoader());
      return new Hook(type.getMethod("isDimensionCard", BaseItemHandler.class), null);
    } catch (ReflectiveOperationException | LinkageError e) {
      org.slf4j.LoggerFactory.getLogger("chestmagnet")
          .error("RSInfinityBooster 跨维度兼容接口不可用，拒绝猜测无线覆盖", e);
      return new Hook(null, e);
    }
  }

  static boolean crossDimension(WirelessTransmitterNetworkNode node) {
    if (!ModList.get().isLoaded(MOD_ID)) return false;
    var hook = LoadedHook.VALUE;
    if (hook.failure() != null)
      throw new IllegalStateException("RSInfinityBooster 跨维度兼容接口不可用", hook.failure());
    try {
      // 使用扩展自己的卡片类型判定，不按显示名或随意移除维度限制。
      return Boolean.TRUE.equals(hook.dimensionCard().invoke(null, node.getUpgrades()));
    } catch (IllegalAccessException | InvocationTargetException | LinkageError e) {
      throw new IllegalStateException("RSInfinityBooster 覆盖判定失败", e);
    }
  }
}
