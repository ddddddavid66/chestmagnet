package dev.chestmagnet;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

final class StackData {
  private static final int LEGACY_FORMAT = 3465; // Minecraft 1.20.1

  private StackData() {}

  static CompoundTag write(HolderLookup.Provider registries, ItemStack stack) {
    return (CompoundTag) stack.saveOptional(registries);
  }

  static ItemStack read(HolderLookup.Provider registries, CompoundTag raw) {
    if (raw.isEmpty()) return ItemStack.EMPTY;
    var candidate = raw;
    // 原版数据修复器不会遍历磁铁组件内部的自定义缓存。
    if (raw.contains("Count")) {
      var fixed = DataFixers.getDataFixer().update(References.ITEM_STACK,
          new Dynamic<>(NbtOps.INSTANCE, raw.copy()), LEGACY_FORMAT,
          SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
      if (!(fixed instanceof CompoundTag tag)) return ItemStack.EMPTY;
      candidate = tag;
    }
    return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), candidate).result().orElse(ItemStack.EMPTY);
  }
}
