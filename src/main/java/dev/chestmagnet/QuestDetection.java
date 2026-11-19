package dev.chestmagnet;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.LoggerFactory;

/** 在收取之后才借用剩余预算，状态不保存库存引用。 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = ChestMagnet.ID)
public final class QuestDetection {
  private static final Map<UUID, Scan> SCANS = new HashMap<>();
  private static final Map<UUID, Priority> PRIORITIES = new HashMap<>();
  private static final int HINT_LIMIT = 32;
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
      if (++destination >= 4) advance();
    }
  }

  /** 提示仅用于缩短刚到账的直接物品任务延迟，绝不作为库存数量或完成证明。 */
  private static final class Priority {
    final Set<Item> pending = new LinkedHashSet<>();
    final Scan scan = new Scan();
    Set<Item> batch = Set.of();
    boolean ready() { return !pending.isEmpty() || !batch.isEmpty(); }
    void start() {
      if (!batch.isEmpty()) return;
      batch = Set.copyOf(pending);
      pending.clear();
      scan.task = scan.destination = 0;
    }
    void finish() {
      scan.release();
      batch = Set.of();
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
      var priority = PRIORITIES.get(p.getUUID());
      if (priority != null && priority.ready() || (scan != null ? scan.next <= tick : !carried(p).isEmpty())) return true;
    }
    return false;
  }

  @net.minecraftforge.eventbus.api.SubscribeEvent
  public static void tagsReload(net.minecraftforge.event.TagsUpdatedEvent event) {
    if (event.getUpdateCause() == net.minecraftforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) clear();
  }

  static void wake(ServerPlayer player, Item item) {
    if (!installed()) return;
    var id = player.getUUID();
    var priority = PRIORITIES.get(id);
    if (priority == null && !carried(player).isEmpty()) {
      priority = new Priority();
      PRIORITIES.put(id, priority);
    }
    if (priority != null && priority.pending.size() < HINT_LIMIT) priority.pending.add(item);
    var scan = SCANS.get(player.getUUID());
    if (scan != null && !scan.cycling)
      scan.next = Math.min(scan.next, Math.max(scan.lastStart + 10, (long) player.server.getTickCount() + 1));
  }

  static void remove(UUID player) {
    var removed = SCANS.remove(player);
    if (removed != null) removed.release();
    var priority = PRIORITIES.remove(player);
    if (priority != null) priority.finish();
  }

  static void clear() {
    SCANS.values().forEach(Scan::release);
    SCANS.clear();
    PRIORITIES.values().forEach(Priority::finish);
    PRIORITIES.clear();
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
      // 优先线每四 tick 最多使用一次；另外三 tick 留给原有完整轮询，含手动存入、旧库存和过滤任务。
      var priority = PRIORITIES.get(player.getUUID());
      if (priority != null && priority.ready() && ((now & 3) == 0 || now < scan.next)) {
        runPriority(player, priority, deadline);
        continue;
      }
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

  private static void runPriority(ServerPlayer player, Priority priority, long deadline) {
    try {
      priority.start();
      if (!QuestCompat.priorityStep(player, priority.scan, priority.batch, deadline)) priority.finish();
    } catch (LinkageError | RuntimeException error) {
      priority.finish();
      LoggerFactory.getLogger(ChestMagnet.ID).warn("FTB 优先查询失败；保留完整库存轮询", error);
    }
    if (!priority.ready()) PRIORITIES.remove(player.getUUID());
  }

  private QuestDetection() {}
}
