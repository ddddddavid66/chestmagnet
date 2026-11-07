package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
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
    final Map<String, Integer> slots = new HashMap<>();
    List<BlockPos> chunkPositions = List.of();
    int cx, cz, offset, sourceCursor;
    BlockPos center = BlockPos.ZERO;
    String range = "";
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
  }

  @SubscribeEvent
  public void stop(ServerStoppedEvent e) {
    STATES.clear();
    Net.clearAll();
    if (ModList.get().isLoaded("lootr")) LootrCompat.clear();
  }

  @SubscribeEvent
  public void block(PlayerInteractEvent.RightClickBlock e) {
    interact(e, e.getLevel().getBlockEntity(e.getPos()));
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
    if (!p.isShiftKeyDown()) {
      toggle(p, s);
      return;
    }
    if (!Sources.eligible(source)) return;
    var ref = Sources.of(source);
    String key = ref.key(p);
    if (!Data.excluded(s, key) && Data.exclusions(s).size() >= Config.EXCLUSIONS.get()) {
      message(p, "排除列表已满，请在配置页面移除记录");
      return;
    }
    boolean excluded = Data.toggle(s, key, ref.label(p));
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
    for (int n = 0; n < players.size(); n++) {
      var p = players.get(Math.floorMod(playerCursor++, players.size()));
      if (!p.isAlive() || p.isSpectator()) continue;
      var magnet = active(p);
      if (magnet.isEmpty()) continue;
      var state = STATES.computeIfAbsent(p.getUUID(), x -> new State());
      try {
        work(p, magnet, state, deadline);
      } catch (Exception ex) {
        Data.tag(magnet).putBoolean("Enabled", false);
        state.status = "兼容接口发生异常，已暂停；请查看日志及恢复缓存";
        org.slf4j.LoggerFactory.getLogger("chestmagnet")
            .error("Transfer paused for {}", p.getUUID(), ex);
        message(p, state.status);
      }
      if (System.nanoTime() >= deadline) break;
    }
  }

  void work(ServerPlayer p, ItemStack m, State s, long deadline) {
    UUID id = Data.id(m);
    String dim = p.level().dimension().location().toString();
    String range = Data.radius(m, "X") + ":" + Data.radius(m, "Y") + ":" + Data.radius(m, "Z");
    if (!id.equals(s.magnet) || !dim.equals(s.dimension) || !range.equals(s.range)) {
      s.sources.clear();
      s.empty.clear();
      s.slots.clear();
      s.cx = s.cz = 0;
      s.offset = 0;
      s.chunkPositions = List.of();
      s.magnet = id;
      s.dimension = dim;
      s.range = range;
      s.center = p.blockPosition();
    }
    if (Data.tag(m).contains("Recovery")) {
      Data.tag(m).putBoolean("Enabled", false);
      s.status = "存在转移恢复缓存，请先取回缓存后再开启";
      notify(p, s);
      return;
    }
    discover(p, m, s, deadline);
    var target = Destinations.get(p, Data.mode(m));
    if (target.unavailable()) {
      s.status = target.reason();
      notify(p, s);
      if (p.containerMenu instanceof MagnetMenu menu && p.level().getGameTime() % 20 == 0)
        Net.sync(p, menu.stack(), s.status);
      return;
    }
    var refs = new ArrayList<>(s.sources.entrySet());
    int probes = 0, moved = 0;
    boolean tried = false, rejected = false;
    String reason = "附近没有待吸收的物品";
    long tick = p.level().getGameTime();
    for (int n = 0;
        n < refs.size()
            && probes < Config.PROBES.get()
            && moved < Config.STACKS.get()
            && System.nanoTime() < deadline;
        n++) {
      var entry = refs.get(Math.floorMod(s.sourceCursor++, refs.size()));
      String key = entry.getKey();
      var ref = entry.getValue();
      var o = ref.resolve(p);
      if (o == null || !Sources.eligible(o) || !Sources.inside(p, m, o)) {
        s.sources.remove(key);
        s.empty.remove(key);
        continue;
      }
      if (Data.excluded(m, ref.key(p)) || s.empty.getOrDefault(key, 0L) > tick) continue;
      if (target.loops(ref, p)) {
        reason = "来源已接入目标 RS 网络";
        continue;
      }
      var inv = Sources.inventory(p, o);
      if (inv == null) continue;
      boolean nonempty = false;
      for (int visit = 0;
          visit < inv.getSlots()
              && probes < Config.PROBES.get()
              && moved < Config.STACKS.get()
              && System.nanoTime() < deadline;
          visit++) {
        int slot = Math.floorMod(s.slots.getOrDefault(key, 0), inv.getSlots());
        s.slots.put(key, slot + 1);
        probes++;
        var sample = inv.extractItem(slot, 64, true);
        if (sample.isEmpty()) continue;
        nonempty = true;
        tried = true;
        var left = target.insert(sample, true);
        int accepted = sample.getCount() - left.getCount();
        if (accepted <= 0) {
          rejected = true;
          reason = target.reason();
          continue;
        }
        // 先拿到真实物品再交给目标；异常中断时保留待核对缓存，不凭模拟结果复制物品。
        var actual = inv.extractItem(slot, accepted, false);
        if (actual.isEmpty()) continue;
        Data.tag(m).put("Recovery", actual.save(new CompoundTag()));
        Data.tag(m).putBoolean("RecoveryUncertain", true);
        var rest = target.insert(actual, false);
        Data.tag(m).remove("RecoveryUncertain");
        int done = actual.getCount() - rest.getCount();
        if (!rest.isEmpty()) {
          Data.tag(m).put("Recovery", rest.save(new CompoundTag()));
          Data.tag(m).putBoolean("RecoveryUncertain", true);
          rest = inv.insertItem(slot, rest, false);
          Data.tag(m).remove("RecoveryUncertain");
        }
        if (rest.isEmpty()) Data.tag(m).remove("Recovery");
        else {
          Data.tag(m).put("Recovery", rest.save(new CompoundTag()));
          Data.tag(m).putBoolean("Enabled", false);
          s.status = "余量无法退回，已存入恢复缓存，请在配置中取回";
          notify(p, s);
          return;
        }
        if (done > 0) {
          moved++;
          s.processed += done;
          p.getInventory().setChanged();
        }
      }
      if (!nonempty && inv.getSlots() <= Config.PROBES.get()) s.empty.put(key, tick + 20);
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

  private void discover(ServerPlayer p, ItemStack m, State s, long deadline) {
    int budget = Config.SCAN.get();
    int rx = Data.radius(m, "X"), ry = Data.radius(m, "Y"), rz = Data.radius(m, "Z");
    var at = p.blockPosition();
    if (p.level().getGameTime() % 5 == 0) {
      var box =
          new AABB(
              at.getX() - rx,
              at.getY() - ry,
              at.getZ() - rz,
              at.getX() + rx + 1,
              at.getY() + ry + 1,
              at.getZ() + rz + 1);
      for (var cart : p.level().getEntitiesOfClass(AbstractMinecartContainer.class, box)) {
        if (budget-- <= 0 || System.nanoTime() >= deadline) break;
        if (Sources.eligible(cart)) {
          var ref = Sources.of(cart);
          s.sources.put(ref.key(p), ref);
        }
      }
    }
    int minX = (at.getX() - rx) >> 4,
        maxX = (at.getX() + rx) >> 4,
        minZ = (at.getZ() - rz) >> 4,
        maxZ = (at.getZ() + rz) >> 4;
    int width = maxX - minX + 1, depth = maxZ - minZ + 1;
    if (s.chunkPositions.isEmpty() || s.offset >= s.chunkPositions.size()) {
      if (p.level().getGameTime() % 10 != 0 && s.cx == 0 && s.cz == 0) return;
      var chunk = p.serverLevel().getChunkSource().getChunkNow(minX + s.cx, minZ + s.cz);
      s.cx++;
      if (s.cx >= width) {
        s.cx = 0;
        s.cz = (s.cz + 1) % depth;
      }
      s.chunkPositions =
          chunk == null ? List.of() : new ArrayList<>(chunk.getBlockEntities().keySet());
      s.offset = 0;
    }
    while (s.offset < s.chunkPositions.size() && budget-- > 0 && System.nanoTime() < deadline) {
      var pos = s.chunkPositions.get(s.offset++);
      if (!p.level().hasChunkAt(pos)) continue;
      var be = p.level().getBlockEntity(pos);
      if (Sources.eligible(be) && Sources.inside(p, m, be)) {
        var ref = Sources.of(be);
        if (s.sources.size() < 4096) s.sources.put(ref.key(p), ref);
      }
    }
  }
}
