package dev.chestmagnet;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public class Engine {
  static final Map<UUID, State> STATES = new HashMap<>();

  static class State {
    UUID magnet;
    String dimension = "", status = "寻找附近容器";
    long messageTick = -100;
    String notified = "";
    long processed;
    final LinkedHashMap<String, Sources.Ref> sources = new LinkedHashMap<>();
    final Map<String, Long> empty = new HashMap<>();
    final Map<String, SlotCycle> slots = new HashMap<>();
    NearbyScan scan = new NearbyScan();
    int mode = -1;
    String range = "";
  }

  private static final class SlotCycle {
    int size, next, visited;
    boolean nonempty;
  }

  public static ItemStack active(ServerPlayer p) {
    for (int i = 0; i < 36; i++) {
      var s = p.getInventory().getItem(i);
      if (s.is(ChestMagnet.MAGNET.get()) && Data.enabled(s)) return s;
    }
    var s = p.getOffhandItem();
    return s.is(ChestMagnet.MAGNET.get()) && Data.enabled(s) ? s : ItemStack.EMPTY;
  }

  static void message(ServerPlayer p, String s) {
    p.displayClientMessage(Component.literal(s), true);
  }

  public static void toggle(ServerPlayer p, ItemStack s) {
    Data.tag(s).putBoolean("Enabled", !Data.enabled(s));
    if (!Data.enabled(s)) StructureJobs.stopForMagnet(p, s, "玩家关闭磁铁");
    else STATES.remove(p.getUUID());
    p.getInventory().setChanged();
    message(
        p, "宝箱磁铁已" + (Data.enabled(s) ? "开启" : "关闭") + " · 吸收模式：" + Data.modeName(Data.mode(s)));
  }

  public static String status(ServerPlayer p) {
    var s = STATES.get(p.getUUID());
    return s == null ? "磁铁已暂停或等待启动" : s.status;
  }

  @SubscribeEvent
  public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
    STATES.remove(e.getEntity().getUUID());
    Net.clearPlayer(e.getEntity().getUUID());
    StructureJobs.cancel(e.getEntity().getUUID());
    AutoStructures.clear(e.getEntity().getUUID());
    QuestDetection.remove(e.getEntity().getUUID());
  }

  @SubscribeEvent
  public void clonePlayer(PlayerEvent.Clone e) {
    if (e.getOriginal() instanceof ServerPlayer old && e.getEntity() instanceof ServerPlayer player)
      { AutoStructures.copy(old, player); CompassTargets.copy(old, player); RecentItems.copy(old, player); }
  }

  @SubscribeEvent
  public void login(PlayerEvent.PlayerLoggedInEvent e) { if(e.getEntity() instanceof ServerPlayer p) CompassTargets.login(p); }

  @SubscribeEvent
  public void stop(ServerStoppedEvent e) {
    STATES.clear();
    Net.clearAll();
    StructureJobs.clear();
    AutoStructures.clear();
    QuestDetection.clear();
    if (ModList.get().isLoaded("lootr")) LootrCompat.clear();
  }

  @SubscribeEvent
  public void block(PlayerInteractEvent.RightClickBlock e) {
    interact(
        e,
        SourceExtras.special(e.getLevel().getBlockState(e.getPos()))
            ? new SourceExtras.Special(e.getPos())
            : e.getLevel().getBlockEntity(e.getPos()));
  }

  @SubscribeEvent
  public void entity(PlayerInteractEvent.EntityInteractSpecific e) {
    interact(e, e.getTarget());
  }

  @SubscribeEvent
  public void entityFallback(PlayerInteractEvent.EntityInteract e) {
    interact(e, e.getTarget());
  }

  private void interact(PlayerInteractEvent e, Object source) {
    ItemStack s = e.getItemStack();
    if (!s.is(ChestMagnet.MAGNET.get())) return;
    e.setCanceled(true);
    e.setCancellationResult(InteractionResult.SUCCESS);
    if (e.getLevel().isClientSide) return;
    var p = (ServerPlayer) e.getEntity();
  }

  static void exclude(ServerPlayer p, ItemStack s, Object source) {
    if (!Sources.eligible(source)) return;
    var ref = Sources.of(source);
    if (!Data.excluded(s, ref.key(p)) && Data.exclusions(s).size() >= Config.EXCLUSIONS.get()) {
      message(p, "排除列表已满");
      return;
    }
    boolean excluded = Data.toggle(s, ref.key(p), ref.label(p));
    message(p, (excluded ? "已排除：" : "已取消排除：") + ref.label(p));
    p.getInventory().setChanged();
  }

  private int playerCursor;

  @SubscribeEvent
  public void tick(TickEvent.ServerTickEvent e) {
    if (e.phase != TickEvent.Phase.END) return;
    var players = e.getServer().getPlayerList().getPlayers();
    if (players.isEmpty()) return;
    long deadline = System.nanoTime() + Config.BUDGET.get() * 1000L;
    Net.flushPreviews(e.getServer());
    // 吸收持续跑满时，每十 tick 为任务检测预留少量原有预算，避免永远排不到。
    long collectionDeadline = e.getServer().getTickCount() % 10 == 0 && QuestDetection.hasPending(players, e.getServer().getTickCount())
        ? deadline - Math.min(350_000L, Config.BUDGET.get() * 100L) : deadline;
    AutoStructures.tick(e.getServer(), collectionDeadline);
    StructureJobs.tick(e.getServer(), collectionDeadline);
    for (int n = 0; n < players.size(); n++) {
      if (System.nanoTime() >= collectionDeadline) break;
      var p = players.get(Math.floorMod(playerCursor++, players.size()));
      if (!p.isAlive() || p.isSpectator()) continue;
      var magnet = active(p);
      if (magnet.isEmpty() || StructureJobs.blocksNormal(p, magnet)) continue;
      var state = STATES.computeIfAbsent(p.getUUID(), x -> new State());
      try {
        Faults.migrate(p, magnet);
        if (Data.enabled(magnet)) work(p, magnet, state, collectionDeadline);
      } catch (Exception ex) {
        Data.tag(magnet).putBoolean("Enabled", false);
        state.status = "处理已暂停：" + String.valueOf(ex.getMessage());
        org.slf4j.LoggerFactory.getLogger("chestmagnet")
            .error("Transfer paused for {}", p.getUUID(), ex);
        Faults.report(p, magnet, ex);
      }
      Faults.clearContext();
      if (System.nanoTime() >= deadline) break;
    }
    QuestDetection.tick(players, deadline);
  }

  void work(ServerPlayer p, ItemStack m, State s, long deadline) {
    UUID id = Data.id(m);
    String dim = p.level().dimension().location().toString();
    String range = Data.radius(m, "X") + ":" + Data.radius(m, "Y") + ":" + Data.radius(m, "Z");
    if (!id.equals(s.magnet) || !dim.equals(s.dimension) || !range.equals(s.range)) {
      s.sources.clear();
      s.empty.clear();
      s.slots.clear();
      s.scan = new NearbyScan();
      s.magnet = id;
      s.dimension = dim;
      s.range = range;
    }
    if (Data.tag(m).contains("Recovery")) {
      Data.tag(m).putBoolean("Enabled", false);
      s.status = "存在转移恢复缓存，请先取回缓存后再开启";
      notify(p, s);
      return;
    }
    Faults.context(p, m, null, "发现来源");
    int mode = Data.mode(m);
    if (mode != s.mode) {
      s.empty.clear();
      s.slots.clear();
      s.mode = mode;
    }
    var at = p.blockPosition();
    int rx = Data.radius(m, "X"), ry = Data.radius(m, "Y"), rz = Data.radius(m, "Z");
    var bounds = new NearbyScan.Box(at.getX() - rx, at.getY() - ry, at.getZ() - rz,
        at.getX() + rx, at.getY() + ry, at.getZ() + rz);
    boolean locked = SourceExtras.option(m, "CollectLocked");
    boolean frames = SourceExtras.option(m, "CollectFrames");
    java.util.function.Consumer<Object> register = o -> {
      if (s.sources.size() < 4096) {
        var ref = Sources.of(o);
        s.sources.put(ref.key(p), ref);
      }
    };
    long now = System.nanoTime();
    long discoveryDeadline = s.sources.isEmpty() ? deadline
        : now + Math.max(0, deadline - now) / 3;
    s.scan.step(p, bounds, locked, frames, discoveryDeadline, register);
    var target = Destinations.tracked(p, mode);
    if (LootCapture.pending(m) && target.unavailable()) {
      s.status = target.reason();
      notify(p, s);
      if (p.containerMenu instanceof MagnetMenu menu && p.level().getGameTime() % 20 == 0)
        Net.sync(p, menu.stack(), s.status);
      return;
    }
    boolean hadPending = LootCapture.pending(m);
    if (!LootCapture.drain(m, target, deadline)) {
      s.status = "开箱产物待接收 · " + target.reason();
      notify(p, s);
      return;
    }
    if (hadPending) {
      s.status = "开箱产物已处理";
      return;
    }
    int sourceCount = Math.min(s.sources.size(), Config.SCAN.get());
    int probes = 0, moved = 0;
    boolean rejected = false;
    String reason = "附近没有待吸收的物品";
    long tick = p.level().getGameTime();
    for (int n = 0;
        n < sourceCount
            && probes < Config.PROBES.get()
            && moved < Config.STACKS.get()
            && System.nanoTime() < deadline;
        n++) {
      var iterator = s.sources.entrySet().iterator();
      var entry = iterator.next();
      String key = entry.getKey();
      var ref = entry.getValue();
      iterator.remove();
      s.sources.put(key, ref);
      if (s.empty.getOrDefault(key, 0L) > tick) continue;
      Faults.context(p, m, ref, "解析来源");
      var o = ref.resolve(p);
      if (o == null || !Sources.eligible(o) || !Sources.inside(p, m, o)) {
        s.sources.remove(key);
        s.empty.remove(key);
        s.slots.remove(key);
        continue;
      }
      String currentKey = ref.key(p);
      if (!key.equals(currentKey)) {
        s.sources.remove(key);
        s.empty.remove(key);
        s.slots.remove(key);
        s.sources.putIfAbsent(currentKey, ref);
        continue;
      }
      if (Faults.skipped(p, m, ref) || Data.excluded(m, key)) continue;
      // 在生成战利品或提取之前验证目的地，空闲扫描不访问 RS。
      if (target.unavailable()) {
        s.status = target.reason();
        notify(p, s);
        return;
      }
      if (target.loops(ref, p)) {
        reason = "来源已接入目标 RS 网络";
        continue;
      }
      Faults.context(p, m, ref, "准备来源");
      var inv = Sources.inventory(p, o, m);
      if (inv == null) continue;
      int size = inv.getSlots();
      var cycle = s.slots.computeIfAbsent(key, k -> new SlotCycle());
      if (size != cycle.size) {
        cycle.size = size;
        cycle.next = cycle.visited = 0;
        cycle.nonempty = false;
      }
      while (cycle.visited < size && probes < Config.PROBES.get()
          && moved < Config.STACKS.get() && System.nanoTime() < deadline) {
        int slot = cycle.next;
        cycle.next = (slot + 1) % size;
        cycle.visited++;
        probes++;
        var sample = inv.extractItem(slot, 64, true);
        if (sample.isEmpty()) continue;
        cycle.nonempty = true;
        var left = target.insert(sample, true);
        int accepted = sample.getCount() - left.getCount();
        if (accepted <= 0) {
          rejected = true;
          reason = target.reason();
          continue;
        }
        // 先拿到真实物品再交给目标；异常中断时保留待核对缓存，不凭模拟结果复制物品。
        int done = Transfer.move(inv, slot, accepted, target, m);
        if (Data.tag(m).contains("Recovery")) {
          Data.tag(m).putBoolean("Enabled", false);
          s.status = "余量或异常已保存至恢复缓存，磁铁暂停";
          notify(p, s);
          return;
        }
        if (done > 0) {
          moved++;
          s.processed += done;
          p.getInventory().setChanged();
        }
      }
      Faults.stage("收尾");
      if (!LootCapture.pending(m) && SourceExtras.needsNearbyFinish(p, o, locked))
        SourceExtras.finish(p, o, inv, m);
      Faults.clearContext();
      if (LootCapture.pending(m)) break;
      if (cycle.visited >= size) {
        if (!cycle.nonempty) s.empty.put(key, tick + 20);
        cycle.visited = 0;
        cycle.nonempty = false;
      }
    }
    if (!LootCapture.pending(m)) {
      Faults.context(p, m, null, "发现特殊来源");
      s.scan.specials(p, locked, deadline, register);
      Faults.clearContext();
    }
    s.status = moved > 0 ? "工作中 · 已处理 " + s.processed + " 件" : reason;
    if (rejected && moved == 0) notify(p, s);
    if (p.containerMenu instanceof MagnetMenu menu && tick % 20 == 0)
      Net.sync(p, menu.stack(), s.status);
  }

  private void notify(ServerPlayer p, State s) {
    long t = p.level().getGameTime();
    if (!s.status.equals(s.notified) && t - s.messageTick >= 60) {
      message(p, s.status);
      s.notified = s.status;
      s.messageTick = t;
    }
  }

}
