package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 自动确认的历史属于玩家而非物品；换磁铁、重搜不能绕过已经完成或明确停止的记录。 */
final class AutoStructures {
  static final String HISTORY = "ChestMagnetStructureHistoryV1";
  private static final Map<UUID, Long> NEXT = new HashMap<>();
  private record Layout(String key, net.minecraft.world.level.levelgen.structure.BoundingBox body) {}
  private static final Map<UUID, Layout> LAYOUTS = new HashMap<>();

  static String key(CompoundTag result) {
    return result.getString("Dimension") + "|" + result.getString("Structure") + "|"
        + new net.minecraft.world.level.ChunkPos(result.getInt("StartX"), result.getInt("StartZ")).toLong();
  }

  static boolean resultValid(ServerPlayer p, CompoundTag result) {
    return result.getInt("Version") == 1 && result.getString("State").equals("FOUND")
        && result.hasUUID("Revision") && result.contains("StartX", Tag.TAG_INT)
        && result.contains("StartZ", Tag.TAG_INT) && !result.getString("Structure").isEmpty()
        && result.getString("Dimension").equals(p.level().dimension().location().toString());
  }

  static CompoundTag history(ServerPlayer p) {
    var root = p.getPersistentData();
    if (!root.contains(HISTORY, Tag.TAG_COMPOUND)) root.put(HISTORY, new CompoundTag());
    return root.getCompound(HISTORY);
  }

  static String state(ServerPlayer p, CompoundTag result) {
    return history(p).getCompound(key(result)).getString("State");
  }

  static void remember(ServerPlayer p, CompoundTag result, String state, String reason) {
    if (!result.hasUUID("Revision")) return;
    var record = new CompoundTag();
    record.putString("State", state);
    record.putString("Reason", reason);
    record.putLong("At", System.currentTimeMillis());
    history(p).put(key(result), record);
  }

  static void allow(ServerPlayer p, CompoundTag result) { history(p).remove(key(result)); }

  /** 无布局时只准靠近展示位置后获取起点；真正吸附还必须通过完整主体距离校验。 */
  static boolean nearAnchor(ServerPlayer p, CompoundTag result) {
    var known = LAYOUTS.get(p.getUUID());
    if (known != null && known.key().equals(key(result))) return StructureJobs.near(p, known.body());
    if (!result.contains("Anchor", Tag.TAG_LONG)) return false;
    var at = BlockPos.of(result.getLong("Anchor"));
    double dx = p.getX() - at.getX(), dz = p.getZ() - at.getZ();
    return dx * dx + dz * dz <= 32 * 32;
  }

  static void layout(ServerPlayer p, StructureJobs.Job j) {
    LAYOUTS.put(p.getUUID(), new Layout(key(j.result), j.body));
  }

  static void tick(MinecraftServer server, long deadline) {
    for (var p : server.getPlayerList().getPlayers()) {
      if (System.nanoTime() >= deadline) return;
      long now = p.level().getGameTime();
      if (now < NEXT.getOrDefault(p.getUUID(), 0L)) continue;
      NEXT.put(p.getUUID(), now + 20);
      if (!p.isAlive() || p.isSpectator() || StructureJobs.JOBS.containsKey(p.getUUID())) continue;
      var magnet = Engine.active(p);
      if (magnet.isEmpty() || !Data.autoStructure(magnet)) continue;
      var result = StructureJobs.snapshot(p);
      if (!resultValid(p, result) || !state(p, result).isEmpty()) continue;
      try {
        StructureJobs.beginAutomatic(p, magnet, result);
      } catch (IllegalArgumentException ex) {
        remember(p, result, "BLOCKED", ex.getMessage());
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "自动结构收取已暂停：" + ex.getMessage() + "；请在磁铁设置中继续"), false);
      } catch (Exception ex) {
        remember(p, result, "BLOCKED", "自动任务准备异常");
        Faults.context(p, magnet, null, "准备自动结构任务");
        try { Faults.report(p, magnet, ex); } finally { Faults.clearContext(); }
      }
    }
  }

  static void clear(UUID player) { NEXT.remove(player); LAYOUTS.remove(player); }
  static void clear() { NEXT.clear(); LAYOUTS.clear(); }

  static void copy(ServerPlayer from, ServerPlayer to) {
    if (from.getPersistentData().contains(HISTORY))
      to.getPersistentData().put(HISTORY, from.getPersistentData().getCompound(HISTORY).copy());
  }
}
