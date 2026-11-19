package dev.chestmagnet;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 只保护仍处于原生锁定状态的首领来源，不生成战利品或猜测附近首领。 */
final class BossProtection {
  private static Class<?> aetherType;
  private static java.lang.reflect.Method aetherLocked;
  private static boolean resolved;

  static boolean enabled(ItemStack magnet) {
    return magnet != null && Data.tag(magnet).getBoolean("ProtectBoss");
  }

  static boolean protects(Object source, ItemStack magnet) {
    if (!enabled(magnet) || !(source instanceof BlockEntity be)) return false;
    String id = ChurchContainers.id(be);
    if (id.equals("goety:pithos") || id.equals("goety:crypt_chest"))
      return ChurchContainers.locked(be);
    if (!resolved) {
      resolved = true;
      try {
        aetherType = Class.forName("com.aetherteam.aether.blockentity.TreasureChestBlockEntity");
        aetherLocked = aetherType.getMethod("getLocked");
      } catch (ReflectiveOperationException | LinkageError ignored) { }
    }
    if (aetherType == null || !aetherType.isInstance(be)) return id.equals("aether:treasure_chest");
    try {
      return aetherLocked == null || Boolean.TRUE.equals(aetherLocked.invoke(be));
    } catch (ReflectiveOperationException | LinkageError ignored) {
      // 已识别的首领来源无法读取锁时保守保护，不冒险绕过原生召唤流程。
      return true;
    }
  }
  private BossProtection() {}
}
