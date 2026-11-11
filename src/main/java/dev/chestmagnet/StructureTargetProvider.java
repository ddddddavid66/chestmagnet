package dev.chestmagnet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
/** 服务端建筑结果提供者。返回 null 表示不负责此玩家；快照沿用 v1 格式。 */
@FunctionalInterface
public interface StructureTargetProvider {
  CompoundTag snapshot(ServerPlayer player);
}
