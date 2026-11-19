package dev.chestmagnet;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class TrackingAlerts {
  // 一个批次按规则和结果合并，避免整座结构收取时每一栈都立即发包。
  private static final Map<UUID, Map<Outcome, Net.TrackingAlert>> PENDING = new HashMap<>();
  private record Outcome(String token, boolean destroyed) {}

  static void deliver(ServerPlayer player, Tracking.Rule rule, CompoundTag display, int stored, int destroyed) {
    if (stored <= 0 && destroyed <= 0) return;
    var icon = rule.key().kind() == Tracking.ITEM ? rule.display() : display;
    var alerts = PENDING.computeIfAbsent(player.getUUID(), id -> new LinkedHashMap<>());
    if (stored > 0) append(alerts, rule.key().token(), icon, stored, false);
    if (destroyed > 0) append(alerts, rule.key().token(), icon, destroyed, true);
  }

  private static void append(Map<Outcome, Net.TrackingAlert> alerts, String token,
      CompoundTag display, int amount, boolean destroyed) {
    if (alerts.size() >= Tracking.LIMIT * 2 && !alerts.containsKey(new Outcome(token, destroyed))) return;
    alerts.compute(new Outcome(token, destroyed), (key, previous) ->
        new Net.TrackingAlert(token, display, previous == null ? amount
            : amount + Math.min(Integer.MAX_VALUE - amount, previous.amount()), destroyed));
  }

  static void flush(MinecraftServer server) {
    if (PENDING.isEmpty() || server.getTickCount() % 10 != 0) return;
    PENDING.forEach((id, alerts) -> {
      var player = server.getPlayerList().getPlayer(id);
      if (player != null) alerts.values().forEach(alert -> Net.trackingAlert(player, alert));
    });
    PENDING.clear();
  }

  static void forget(UUID id) { PENDING.remove(id); }

  private TrackingAlerts() {}
}
