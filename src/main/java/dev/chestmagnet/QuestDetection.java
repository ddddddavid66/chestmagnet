package dev.chestmagnet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.LoggerFactory;

/** 在收取之后才借用剩余预算，状态不保存库存引用。 */
final class QuestDetection {
  private static final Map<UUID, Scan> SCANS = new HashMap<>();
  private static final long SLICE_NANOS = 500_000L;
  private static int playerCursor;
  private static Boolean installed;

  static final class Scan {
    UUID magnet;
    int task, destination;
    long next, lastStart;
    boolean cycling;
  }

  private static boolean installed() {
    if (installed == null) installed = ModList.get().isLoaded("ftbquests");
    return installed;
  }

  static void wake(ServerPlayer player) {
    if (!installed()) return;
    var scan = SCANS.get(player.getUUID());
    if (scan != null && !scan.cycling)
      scan.next = Math.min(scan.next, Math.max(scan.lastStart + 10, (long) player.server.getTickCount() + 1));
  }

  static void remove(UUID player) {
    SCANS.remove(player);
  }

  static void clear() {
    SCANS.clear();
    playerCursor = 0;
  }

  static void tick(List<ServerPlayer> players, long engineDeadline) {
    if (!installed() || players.isEmpty()) return;
    long deadline = Math.min(engineDeadline, System.nanoTime() + SLICE_NANOS);
    for (int i = 0; i < players.size() && System.nanoTime() < deadline; i++) {
      var player = players.get(Math.floorMod(playerCursor++, players.size()));
      if (!player.isAlive() || player.isSpectator()) { remove(player.getUUID()); continue; }
      var magnet = Engine.active(player);
      if (magnet.isEmpty() || !Data.questDetection(Data.tag(magnet))) {
        remove(player.getUUID());
        continue;
      }
      var scan = SCANS.computeIfAbsent(player.getUUID(), ignored -> new Scan());
      var id = Data.id(magnet);
      if (!id.equals(scan.magnet)) {
        scan.magnet = id;
        scan.next = 0;
        scan.task = scan.destination = 0;
        scan.cycling = false;
      }
      // 服务器时钟不会因换维度而把下一次检查推迟到旧世界的时间。
      long now = player.server.getTickCount();
      if (now < scan.next) continue;
      if (!scan.cycling) {
        scan.task = scan.destination = 0;
        scan.lastStart = now;
      }
      try {
        scan.cycling = QuestCompat.step(player, scan, deadline);
        scan.next = scan.cycling ? now + 1 : now + 40;
      } catch (LinkageError | RuntimeException error) {
        scan.cycling = false;
        scan.next = now + 1200;
        LoggerFactory.getLogger(ChestMagnet.ID).warn("FTB 检测失败，不影响磁铁收取", error);
      }
    }
  }

  private QuestDetection() {}
}
