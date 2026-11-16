package dev.chestmagnet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.slf4j.LoggerFactory;

/** 在收取之后才借用剩余预算，状态不保存库存引用。 */
@net.neoforged.fml.common.EventBusSubscriber(modid = ChestMagnet.ID)
public final class QuestDetection {
  private static final Map<UUID, Scan> SCANS = new HashMap<>();
  private static final long SLICE_NANOS = 500_000L;
  private static int playerCursor;
  private static Boolean installed;

  static final class Scan {
    UUID magnet;
    int task, destination;
    long next, lastStart;
    boolean cycling;
    Object current, team, sample;
    QuestMatch match;
    QuestStock.Reader reader;

    void closeReader() {
      var old = reader;
      reader = null;
      if (old != null) old.close();
    }

    void release() {
      closeReader();
      current = team = sample = null;
      match = null;
    }

    void advance() {
      release();
      task++;
      destination = 0;
    }

    void nextDestination() {
      closeReader();
      if (++destination >= 3) advance();
    }
  }

  private static boolean installed() {
    if (installed == null) installed = ModList.get().isLoaded("ftbquests");
    return installed;
  }

  /** 任务检测独立于吸附总开关，按物品栏顺序选首枚开启任务检测的磁铁。 */
  static ItemStack carried(ServerPlayer player) {
    for (int slot = 0; slot < 36; slot++) {
      var stack = player.getInventory().getItem(slot);
      if (detects(stack)) return stack;
    }
    var offhand = player.getOffhandItem();
    return detects(offhand) ? offhand : ItemStack.EMPTY;
  }

  private static boolean detects(ItemStack stack) {
    return stack.is(ChestMagnet.MAGNET.get()) && Data.questDetection(Data.tag(stack));
  }

  static boolean hasPending(List<ServerPlayer> players, int tick) {
    if (!installed()) return false;
    for (var p : players) {
      var scan = SCANS.get(p.getUUID());
      if (scan != null ? scan.next <= tick : !carried(p).isEmpty()) return true;
    }
    return false;
  }

  @net.neoforged.bus.api.SubscribeEvent
  public static void tagsReload(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
    if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) clear();
  }

  static void wake(ServerPlayer player) {
    if (!installed()) return;
    var scan = SCANS.get(player.getUUID());
    if (scan != null && !scan.cycling)
      scan.next = Math.min(scan.next, Math.max(scan.lastStart + 10, (long) player.server.getTickCount() + 1));
  }

  static void remove(UUID player) {
    var removed = SCANS.remove(player);
    if (removed != null) removed.release();
  }

  static void clear() {
    SCANS.values().forEach(Scan::release);
    SCANS.clear();
    playerCursor = 0;
  }

  static void tick(List<ServerPlayer> players, long engineDeadline) {
    if (!installed() || players.isEmpty()) return;
    long deadline = Math.min(engineDeadline, System.nanoTime() + SLICE_NANOS);
    for (int i = 0; i < players.size() && System.nanoTime() < deadline; i++) {
      var player = players.get(Math.floorMod(playerCursor++, players.size()));
      if (!player.isAlive() || player.isSpectator()) { remove(player.getUUID()); continue; }
      var magnet = carried(player);
      if (magnet.isEmpty()) {
        remove(player.getUUID());
        continue;
      }
      var scan = SCANS.computeIfAbsent(player.getUUID(), ignored -> new Scan());
      var id = Data.id(magnet);
      if (!id.equals(scan.magnet)) {
        scan.release();
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
        scan.advance();
        scan.cycling = true;
        scan.next = now + 40;
        LoggerFactory.getLogger(ChestMagnet.ID).warn("FTB 检测失败，不影响磁铁收取", error);
      }
    }
  }

  private QuestDetection() {}
}
