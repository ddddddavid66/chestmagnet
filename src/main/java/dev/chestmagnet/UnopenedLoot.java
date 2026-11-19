package dev.chestmagnet;

import java.lang.reflect.Field;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

final class UnopenedLoot {
  // 1.20.1 的唯一战利品表字段；直接读取，不用 getItem()/isEmpty() 触发生成。
  private static final Field TABLE = findTable();

  private static Field findTable() {
    Field candidate = null;
    for (var field : RandomizableContainerBlockEntity.class.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != ResourceLocation.class)
        continue;
      if (candidate != null) throw new IllegalStateException("战利品表字段不唯一");
      candidate = field;
    }
    if (candidate == null || !candidate.trySetAccessible())
      throw new IllegalStateException("无法读取未生成的战利品表");
    return candidate;
  }

  static ResourceLocation table(RandomizableContainerBlockEntity tile) {
    try {
      return (ResourceLocation) TABLE.get(tile);
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException("无法读取未生成的战利品表", ex);
    }
  }
}
