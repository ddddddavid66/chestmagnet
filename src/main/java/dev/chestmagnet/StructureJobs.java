package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.phys.AABB;

/** 完整收取范围与预览范围独立；没有任何建筑 ID 特例。所有世界访问在服务端线程。 */
public final class StructureJobs {
  static final TicketType<UUID> TICKET =
      TicketType.create("chestmagnet_structure", Comparator.comparing(UUID::toString));
  static final Map<UUID, Job> JOBS = new LinkedHashMap<>();

  static class Job {
    ServerLevel level;
    UUID nonce = UUID.randomUUID(), magnet, revision;
    CompoundTag result;
    BoundingBox body, region;
    Structure structure;
    ChunkPos start;
    ArrayDeque<ChunkPos> waiting = new ArrayDeque<>();
    Map<ChunkPos, Long> tickets = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    ArrayDeque<Sources.Ref> sources = new ArrayDeque<>();
    ChunkPos current;
    int slot, done, total, found, processed;
    boolean running, paused, previewDone, complete, viewing = true;
    String status = "准备预览";
    long lastRequest = -100, created;
    int previewY, previewIndex, previewSent;
    String dimension;
    List<Long> markers = new ArrayList<>();
  }

  static CompoundTag snapshot(ServerPlayer p) {
    try {
      return (CompoundTag)
          Class.forName("com.chaosthedude.explorerscompass.api.StructureSearchSnapshot")
              .getMethod("getSnapshot", ServerPlayer.class)
              .invoke(null, p);
    } catch (ReflectiveOperationException e) {
      var t = new CompoundTag();
      t.putString("State", "UNSUPPORTED");
      return t;
    }
  }

  static ItemStack held(ServerPlayer p) {
    return p.getMainHandItem().is(ChestMagnet.MAGNET.get())
        ? p.getMainHandItem()
        : p.getOffhandItem();
  }

  static void tell(ServerPlayer p, String s) {
    p.displayClientMessage(Component.literal(s), false);
    var t = new CompoundTag();
    t.putString("Error", s);
    Net.structure(p, t);
  }

  static boolean near(ServerPlayer p, BoundingBox b) {
    double dx = Math.max(Math.max(b.minX() - p.getX(), 0), p.getX() - b.maxX()),
        dz = Math.max(Math.max(b.minZ() - p.getZ(), 0), p.getZ() - b.maxZ());
    double dy = Math.max(Math.max(b.minY() - p.getY(), 0), p.getY() - b.maxY());
    return dx * dx + dz * dz <= 1024 && dy <= 32;
  }

  static BoundingBox bounds(StructureStart start) {
    int x = Integer.MAX_VALUE, y = x, z = x, X = Integer.MIN_VALUE, Y = X, Z = X;
    for (var piece : start.getPieces()) {
      var b = piece.getBoundingBox();
      x = Math.min(x, b.minX());
      y = Math.min(y, b.minY());
      z = Math.min(z, b.minZ());
      X = Math.max(X, b.maxX());
      Y = Math.max(Y, b.maxY());
      Z = Math.max(Z, b.maxZ());
    }
    return new BoundingBox(x, y, z, X, Y, Z);
  }

  static StructureStart start(Job j) {
    var chunk = j.level.getChunkSource().getChunkNow(j.start.x, j.start.z);
    return chunk == null ? null : chunk.getStartForStructure(j.structure);
  }

  static void geometry(Job j) {
    var st = start(j);
    if (st == null || !st.isValid() || st.getPieces().isEmpty())
      throw new IllegalArgumentException("无法取得可靠建筑布局，请靠近目标或重新搜索");
    j.body = bounds(st);
    int m = Config.STRUCTURE_MARGIN.get();
    j.region =
        new BoundingBox(
            j.body.minX() - m,
            j.level.getMinBuildHeight(),
            j.body.minZ() - m,
            j.body.maxX() + m,
            j.level.getMaxBuildHeight() - 1,
            j.body.maxZ() + m);
  }

  static boolean contains(Job j, BlockPos p) {
    return j.region.isInside(p);
  }

  static boolean contains(Job j, Object o) {
    return contains(
        j,
        o instanceof net.minecraft.world.entity.Entity e
            ? e.blockPosition()
            : ((net.minecraft.world.level.block.entity.BlockEntity) o).getBlockPos());
  }

  public static boolean blocksNormal(ServerPlayer p, ItemStack s) {
    var j = JOBS.get(p.getUUID());
    return j != null && Data.id(s).equals(j.magnet) && (!j.complete || j.viewing);
  }

  static void release(Job j) {
    for (var at : j.tickets.keySet())
      j.level.getChunkSource().removeRegionTicket(TICKET, at, 0, j.nonce);
    j.tickets.clear();
  }

  public static void cancel(UUID player) {
    var j = JOBS.remove(player);
    if (j != null) release(j);
  }

  public static void clear() {
    for (var id : List.copyOf(JOBS.keySet())) cancel(id);
  }

  static int ticketCount() {
    return JOBS.values().stream().mapToInt(j -> j.tickets.size()).sum();
  }

  public static void request(ServerPlayer p, Net.Request req) {
    if (req.action() == 26) {
      var existing = JOBS.get(p.getUUID());
      if (existing != null) {
        existing.viewing = false;
        if (!existing.running || existing.complete) cancel(p.getUUID());
      }
      return;
    }
    if (req.action() == 20) {
      var item = held(p);
      if (!item.is(ChestMagnet.MAGNET.get())) return;
      var result = snapshot(p);
      String state = result.getString("State");
      if (state.equals("FOUND") && (!result.hasUUID("Revision") || result.getInt("Version") != 1)) {
        tell(p, "旧搜索结果缺少结构起点，请重新搜索");
        return;
      }
      if (!state.equals("FOUND")) {
        tell(
            p,
            switch (state) {
              case "SEARCHING" -> "指南针正在搜索，请等待结果";
              case "NON_STRUCTURE" -> "当前搜索结果不是建筑结构，请搜索建筑";
              case "UNSUPPORTED" -> "请安装探险者指南针 1.3.4 联动版，并重新搜索";
              case "NOT_FOUND", "CANCELLED" -> "当前搜索无有效结果，请重新搜索";
              default -> "你还没有进行搜索";
            });
        return;
      }
      if (!result.getString("Dimension").equals(p.level().dimension().location().toString())) {
        tell(p, "目标在其他维度");
        return;
      }
      var old = JOBS.get(p.getUUID());
      if (old != null && valid(p, old)) {
        old.viewing = true;
        old.previewDone = false;
        old.previewIndex = 0;
        old.previewSent = 0;
        send(p, old, true);
        return;
      }
      cancel(p.getUUID());
      var j = new Job();
      j.level = p.serverLevel();
      j.result = result;
      j.dimension = result.getString("Dimension");
      j.magnet = Data.id(item);
      j.revision = result.getUUID("Revision");
      j.start = new ChunkPos(result.getInt("StartX"), result.getInt("StartZ"));
      j.structure =
          j.level
              .registryAccess()
              .registryOrThrow(Registries.STRUCTURE)
              .get(ResourceLocation.tryParse(result.getString("Structure")));
      try {
        if (j.structure == null) throw new IllegalArgumentException("建筑注册信息已失效，请重新搜索");
        geometry(j);
        if (!near(p, j.body)) throw new IllegalArgumentException("请靠近建筑：水平及垂直距离均需不超过32格");
        int count =
            ((j.region.maxX() >> 4) - (j.region.minX() >> 4) + 1)
                * ((j.region.maxZ() >> 4) - (j.region.minZ() >> 4) + 1);
        if (count > Config.STRUCTURE_CHUNKS.get())
          throw new IllegalArgumentException(
              "建筑扫描区域过大：" + count + " 区块，服务端上限 " + Config.STRUCTURE_CHUNKS.get());
        j.total = count;
        j.previewY = j.body.maxY();
        j.created = j.level.getGameTime();
        JOBS.put(p.getUUID(), j);
        send(p, j, true);
      } catch (IllegalArgumentException e) {
        tell(p, e.getMessage());
      }
      return;
    }
    var j = JOBS.get(p.getUUID());
    if (j == null || !j.nonce.equals(req.identity())) return;
    if (req.action() == 22) {
      cancel(p.getUUID());
      return;
    }
    if (!valid(p, j)) {
      cancel(p.getUUID());
      tell(p, "目标、位置或手持磁铁已变化，请重新确认");
      return;
    }
    if (req.action() == 23) {
      Data.tag(held(p)).putInt("Mode", Math.floorMod(req.value(), 3));
      send(p, j, false);
      return;
    }
    if (req.action() == 25) {
      j.previewY = Math.max(j.region.minY(), Math.min(j.region.maxY(), req.value()));
      j.previewDone = false;
      j.previewIndex = 0;
      j.previewSent = 0;
      send(p, j, true);
      return;
    }
    if (req.action() == 21 || req.action() == 24) {
      if (j.complete) {
        j.complete = false;
        j.running = false;
        j.done = 0;
        j.found = 0;
        j.seen.clear();
        j.markers.clear();
        j.waiting.clear();
      }
      if (Data.tag(held(p)).contains("Recovery")) {
        j.paused = true;
        j.status = "先在磁铁设置中处理恢复缓存，再继续收取";
        send(p, j, false);
        return;
      }
      if (!j.level
              .getWorldBorder()
              .isWithinBounds(new BlockPos(j.region.minX(), 0, j.region.minZ()))
          || !j.level
              .getWorldBorder()
              .isWithinBounds(new BlockPos(j.region.maxX(), 0, j.region.maxZ()))) {
        cancel(p.getUUID());
        tell(p, "扫描区域超出世界边界，无法完整收取");
        return;
      }
      if (!j.running) {
        geometry(j);
        for (int x = j.region.minX() >> 4; x <= j.region.maxX() >> 4; x++)
          for (int z = j.region.minZ() >> 4; z <= j.region.maxZ() >> 4; z++)
            j.waiting.add(new ChunkPos(x, z));
        j.running = true;
      }
      j.paused = false;
      j.status = "加载并收取中";
      Data.tag(held(p)).putBoolean("Enabled", true);
      send(p, j, false);
    }
  }

  static boolean valid(ServerPlayer p, Job j) {
    var h = held(p);
    var s = snapshot(p);
    return p.isAlive()
        && !p.isSpectator()
        && p.serverLevel() == j.level
        && h.is(ChestMagnet.MAGNET.get())
        && Data.id(h).equals(j.magnet)
        && s.getString("State").equals("FOUND")
        && s.hasUUID("Revision")
        && s.getUUID("Revision").equals(j.revision)
        && near(p, j.body);
  }

  static void send(ServerPlayer p, Job j, boolean reset) {
    if (p.connection == null) return;
    var t = new CompoundTag();
    t.putUUID("Nonce", j.nonce);
    t.putString("Structure", j.result.getString("Structure"));
    t.putString("Status", j.status);
    t.putIntArray(
        "Body",
        new int[] {
          j.body.minX(), j.body.minY(), j.body.minZ(), j.body.maxX(), j.body.maxY(), j.body.maxZ()
        });
    t.putIntArray(
        "Region",
        new int[] {
          j.region.minX(),
          j.region.minY(),
          j.region.minZ(),
          j.region.maxX(),
          j.region.maxY(),
          j.region.maxZ()
        });
    t.putIntArray(
        "PreviewBounds",
        new int[] {
          j.body.minX(),
          Math.max(j.region.minY(), Math.min(j.body.minY(), j.previewY - 32)),
          j.body.minZ(),
          j.body.maxX(),
          j.previewY,
          j.body.maxZ()
        });
    t.putLongArray("Containers", j.markers);
    t.putInt("Mode", Data.mode(held(p)));
    t.putInt("Done", j.done);
    t.putInt("Total", j.total);
    t.putInt("Found", j.found);
    t.putInt("Processed", j.processed);
    t.putBoolean("Running", j.running);
    t.putBoolean("Paused", j.paused);
    t.putBoolean("Reset", reset);
    t.putBoolean("Open", reset);
    t.putInt("Slice", j.previewY);
    Net.structure(p, t);
  }

  public static void tick(net.minecraft.server.MinecraftServer server, long deadline) {
    for (var entry : List.copyOf(JOBS.entrySet())) {
      var p = server.getPlayerList().getPlayer(entry.getKey());
      var j = entry.getValue();
      if (p == null || !valid(p, j) || j.running && !Data.enabled(held(p))) {
        cancel(entry.getKey());
        if (p != null) tell(p, "结构任务已停止：目标、距离或磁铁状态发生变化");
        continue;
      }
      try {
        if (!j.previewDone) preview(p, j, deadline);
        if (j.running && !j.paused) collect(p, j, deadline);
        if (j.level.getGameTime() % 20 == 0) send(p, j, false);
      } catch (Exception ex) {
        release(j);
        j.paused = true;
        j.status = "任务异常已暂停，请检查恢复缓存与日志";
        send(p, j, false);
        org.slf4j.LoggerFactory.getLogger("chestmagnet").error("Structure task paused", ex);
      }
      if (System.nanoTime() >= deadline) break;
    }
  }

  static void preview(ServerPlayer p, Job j, long deadline) {
    int w = j.body.getXSpan(), d = j.body.getZSpan();
    int low = Math.max(j.region.minY(), Math.min(j.body.minY(), j.previewY - 32)),
        high = j.previewY;
    long volume = (long) w * d * (high - low + 1);
    int cap = (int) Math.min(262144, volume), visits = 0;
    var pos = new ArrayList<Long>();
    var states = new ArrayList<Integer>();
    while (j.previewIndex < cap
        && visits++ < 4096
        && pos.size() < 512
        && System.nanoTime() < deadline) {
      int k = j.previewIndex++;
      var at = new BlockPos(j.body.minX() + k % w, low + k / (w * d), j.body.minZ() + (k / w) % d);
      if (!j.level.hasChunkAt(at)) continue;
      var chunk = j.level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
      if (chunk == null) continue;
      var s = chunk.getBlockState(at);
      if (s.isAir()) continue;
      if (j.previewSent >= 16384) {
        j.previewIndex = cap;
        break;
      }
      pos.add(at.asLong());
      states.add(Block.getId(s));
      j.previewSent++;
    }
    if (!pos.isEmpty()) {
      var t = new CompoundTag();
      t.putUUID("Nonce", j.nonce);
      t.putLongArray("Positions", pos);
      t.putIntArray("States", states);
      Net.structure(p, t);
    }
    if (j.previewIndex >= cap) {
      j.previewDone = true;
      j.status = j.running ? j.status : "现场局部预览（已加载区域；大结构可能简化）";
      send(p, j, false);
    }
  }

  static boolean doubleReady(ServerPlayer p, Job j, Object o) {
    if (!(o instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) return true;
    var state = chest.getBlockState();
    if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
        || state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
            == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return true;
    var other =
        chest
            .getBlockPos()
            .relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
    if (!contains(j, other)) return true;
    var cp = new ChunkPos(other);
    var ready = j.level.getChunkSource().getChunkNow(cp.x, cp.z);
    if (ready != null && ready.getFullStatus().isOrAfter(FullChunkStatus.FULL)) return true;
    if (!j.tickets.containsKey(cp) && j.level.getGameTime() - j.lastRequest >= 5) {
      for (var at : List.copyOf(j.tickets.keySet()))
        if (!at.equals(j.current)) {
          j.level.getChunkSource().removeRegionTicket(TICKET, at, 0, j.nonce);
          j.tickets.remove(at);
          j.waiting.addFirst(at);
        }
      if (ticketCount() < 4) {
        j.waiting.remove(cp);
        j.level.getChunkSource().addRegionTicket(TICKET, cp, 0, j.nonce);
        j.tickets.put(cp, j.level.getGameTime());
        j.lastRequest = j.level.getGameTime();
      }
    }
    return false;
  }

  static void collect(ServerPlayer p, Job j, long deadline) {
    long now = j.level.getGameTime();
    if (j.tickets.size() < 2
        && ticketCount() < 4
        && !j.waiting.isEmpty()
        && now - j.lastRequest >= 5) {
      var at = j.waiting.removeFirst();
      j.level.getChunkSource().addRegionTicket(TICKET, at, 0, j.nonce);
      j.tickets.put(at, now);
      j.lastRequest = now;
    }
    if (j.current == null) {
      for (var e : j.tickets.entrySet()) {
        var chunk = j.level.getChunkSource().getChunkNow(e.getKey().x, e.getKey().z);
        if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)) {
          if (now - e.getValue() > 600) {
            j.status = "区块加载超时，未全部完成；可继续";
            j.paused = true;
            releaseForPause(j);
            return;
          }
          continue;
        }
        j.current = e.getKey();
        geometry(j);
        for (var at : chunk.getBlockEntitiesPos()) {
          if (!contains(j, at)) continue;
          var be = chunk.getBlockEntity(at);
          if (Sources.eligible(be)) {
            var ref = Sources.of(be);
            if (j.seen.add(ref.key(p))) {
              j.sources.add(ref);
              if (j.markers.size() < 2048) j.markers.add(at.asLong());
            }
          }
        }
        var area =
            new AABB(
                chunk.getPos().getMinBlockX(),
                j.region.minY(),
                chunk.getPos().getMinBlockZ(),
                chunk.getPos().getMaxBlockX() + 1,
                j.region.maxY() + 1,
                chunk.getPos().getMaxBlockZ() + 1);
        for (var cart : j.level.getEntitiesOfClass(AbstractMinecartContainer.class, area))
          if (contains(j, cart) && Sources.eligible(cart)) {
            var ref = Sources.of(cart);
            if (j.seen.add(ref.key(p))) {
              j.sources.add(ref);
              if (j.markers.size() < 2048) j.markers.add(cart.blockPosition().asLong());
            }
          }
        j.found += j.sources.size();
        break;
      }
    }
    int probes = 0, moved = 0;
    var magnet = held(p);
    var target = Destinations.get(p, Data.mode(magnet));
    if (target.unavailable()) {
      j.status = target.reason();
      j.paused = true;
      releaseForPause(j);
      return;
    }
    while (j.current != null
        && !j.sources.isEmpty()
        && probes < Config.PROBES.get()
        && moved < Config.STACKS.get()
        && System.nanoTime() < deadline) {
      var ref = j.sources.peek();
      var o = ref.resolve(p);
      if (o == null
          || !contains(j, o)
          || Data.excluded(magnet, ref.key(p))
          || target.loops(ref, p)
          || ref.members(p).stream().anyMatch(at -> !contains(j, at))) {
        j.sources.remove();
        j.slot = 0;
        continue;
      }
      if (!doubleReady(p, j, o)) return;
      var inv = Sources.inventory(p, o);
      if (inv == null) {
        j.sources.remove();
        j.slot = 0;
        continue;
      }
      if (j.slot >= inv.getSlots()) {
        j.sources.remove();
        j.slot = 0;
        continue;
      }
      int slot = j.slot;
      probes++;
      var sample = inv.extractItem(slot, 64, true);
      if (sample.isEmpty()) {
        j.slot++;
        continue;
      }
      int accepted = sample.getCount() - target.insert(sample, true).getCount();
      if (accepted <= 0) {
        j.status = target.reason();
        j.paused = true;
        releaseForPause(j);
        return;
      }
      int n = Transfer.move(inv, slot, accepted, target, magnet);
      j.processed += n;
      if (n > 0) moved++;
      else j.slot++;
      if (Data.tag(magnet).contains("Recovery")) {
        j.status = "转移余量已进入恢复缓存，任务暂停";
        j.paused = true;
        releaseForPause(j);
        return;
      }
    }
    if (j.current != null && j.sources.isEmpty() && !j.paused) {
      var at = j.current;
      j.level.getChunkSource().removeRegionTicket(TICKET, at, 0, j.nonce);
      j.tickets.remove(at);
      j.current = null;
      j.done++;
    }
    if (j.current == null && j.tickets.isEmpty() && j.waiting.isEmpty() && !j.paused) {
      j.complete = true;
      j.status = "全部扫描收取完成 · 已处理 " + j.processed + " 件";
      j.paused = true;
      Engine.message(p, j.status);
      send(p, j, false);
      if (!j.viewing) cancel(p.getUUID());
    }
  }

  static void releaseForPause(Job j) {
    for (var at : j.tickets.keySet()) if (!at.equals(j.current)) j.waiting.addFirst(at);
    release(j);
    if (j.current != null) {
      j.waiting.addFirst(j.current);
      j.current = null;
    } /* 保留来源队列和槽位，恢复时不丢掉未接收余量。 */
  }
}
