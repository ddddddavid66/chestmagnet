package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.phys.AABB;

/** 完整收取范围与预览范围独立；没有任何建筑 ID 特例。所有世界访问在服务端线程。 */
public final class StructureJobs {
  static final TicketType<UUID> TICKET =
      TicketType.create("chestmagnet_structure", Comparator.comparing(UUID::toString));
  static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
  private static int jobCursor;

  static class Job {
    ServerLevel level;
    UUID nonce = UUID.randomUUID(), magnet, revision;
    CompoundTag result;
    BoundingBox body, region;
    // 每个分段各自外扩后取并集；外包络只供镜头和摘要，不能决定搜箱范围。
    List<BoundingBox> footprint = List.of();
    Set<ChunkPos> plan = new LinkedHashSet<>();
    Structure structure;
    ChunkPos start;
    ArrayDeque<ChunkPos> waiting = new ArrayDeque<>();
    Map<ChunkPos, Long> tickets = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    ArrayDeque<Sources.Ref> sources = new ArrayDeque<>();
    ChunkPos current, resume;
    SourceExtras.Scan specialScan;
    List<BlockPos> blockEntities = List.of();
    List<UUID> entityIds = List.of();
    int blockCursor, entityCursor;
    int slot, done, total, found, processed, loadRequests;
    int skipped, isolatedBefore;
    boolean automatic, preparing, waitingTarget;
    long waitStartedNanos, nextTargetCheck;
    ItemStack waitSample = ItemStack.EMPTY;
    final Set<String> skippedKeys = new HashSet<>();
    PlanPreview.State previewState;
    boolean running, paused, previewDone, complete, viewing = true;
    String status = "准备预览";
    long lastRequest = -100, created;
    int previewY, previewIndex, previewSent;
    String dimension;
    List<Long> markers = new ArrayList<>();
  }

  static CompoundTag snapshot(ServerPlayer p) {
    return CompassTargets.snapshot(p);
  }

  static ItemStack held(ServerPlayer p) {
    return p.getMainHandItem().is(ChestMagnet.MAGNET.get())
        ? p.getMainHandItem()
        : p.getOffhandItem();
  }

  /** 自动任务允许磁铁留在物品栏；手动任务仍必须手持原磁铁。 */
  static ItemStack item(ServerPlayer p, Job j) {
    var item = j.automatic ? Engine.active(p) : held(p);
    return item.is(ChestMagnet.MAGNET.get()) && Data.id(item).equals(j.magnet)
        ? item : ItemStack.EMPTY;
  }

  static Job create(ServerPlayer p, ItemStack magnet, CompoundTag result, boolean automatic) {
    var j = new Job();
    j.level = p.serverLevel();
    j.result = result.copy();
    j.dimension = result.getString("Dimension");
    j.magnet = Data.id(magnet);
    j.revision = result.getUUID("Revision");
    j.start = new ChunkPos(result.getInt("StartX"), result.getInt("StartZ"));
    j.structure = j.level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        .get(ResourceLocation.tryParse(result.getString("Structure")));
    if (j.structure == null) throw new IllegalArgumentException("建筑注册信息已失效，请重新搜索");
    j.automatic = automatic;
    j.viewing = !automatic;
    j.created = j.level.getGameTime();
    j.isolatedBefore = Data.tag(magnet).getList("Quarantined", 10).size();
    return j;
  }

  static boolean beginAutomatic(ServerPlayer p, ItemStack magnet, CompoundTag result) {
    var j = create(p, magnet, result, true);
    if (!full(j, j.start)) {
      if (!AutoStructures.nearAnchor(p, result)) return false;
      j.preparing = true;
      j.status = "正在获取建筑布局";
      JOBS.put(p.getUUID(), j);
      return true;
    }
    geometry(j);
    AutoStructures.layout(p, j);
    if (!near(p, j.body)) return false;
    JOBS.put(p.getUUID(), j);
    try { resume(p, j, magnet); }
    catch (RuntimeException ex) { cancel(p.getUUID()); throw ex; }
    return true;
  }

  /** 设置页操作不伪造客户端确认包；服务器只接受已授权的磁铁身份。 */
  static void control(ServerPlayer p, ItemStack magnet, boolean stop) {
    var j = JOBS.get(p.getUUID());
    if (j != null && !j.magnet.equals(Data.id(magnet))) return;
    if (stop) {
      if (j != null) {
        if (!j.complete) AutoStructures.remember(p, j.result, "BLOCKED", "玩家停止任务");
        cancel(p.getUUID());
      } else {
        var result = snapshot(p);
        if (AutoStructures.resultValid(p, result) && !AutoStructures.state(p, result).equals("COMPLETE"))
          AutoStructures.remember(p, result, "BLOCKED", "玩家停止任务");
      }
      return;
    }
    if (j != null) {
      if (!valid(p, j)) throw new IllegalArgumentException("目标、距离或磁铁身份已经变化，请重新确认");
      AutoStructures.allow(p, j.result);
      resume(p, j, magnet);
      return;
    }
    var result = snapshot(p);
    if (!AutoStructures.resultValid(p, result)) throw new IllegalArgumentException("当前没有有效结构目标");
    if (AutoStructures.state(p, result).equals("COMPLETE"))
      throw new IllegalArgumentException("当前结构已经完成；需要重扫时请使用结构收取快捷键");
    var active = Engine.active(p);
    if (active.isEmpty() || !Data.id(active).equals(Data.id(magnet)))
      throw new IllegalArgumentException("请启用并选择当前物品栏中顺序最前的磁铁");
    AutoStructures.allow(p, result);
    if (!beginAutomatic(p, magnet, result)) throw new IllegalArgumentException("请靠近指南针目标或建筑主体");
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
    buildFootprint(j, st.getPieces().stream().map(StructurePiece::getBoundingBox).toList());
  }

  static void buildFootprint(Job j, List<BoundingBox> pieces) {
    int m = Config.STRUCTURE_MARGIN.get();
    var areas = new ArrayList<BoundingBox>();
    var chunks = new LinkedHashSet<ChunkPos>();
    int limit = Config.STRUCTURE_CHUNKS.get();
    for (var b : pieces) {
      var area = new BoundingBox(b.minX() - m, j.region.minY(), b.minZ() - m,
          b.maxX() + m, j.region.maxY(), b.maxZ() + m);
      areas.add(area);
      long count = ((long)(area.maxX() >> 4) - (area.minX() >> 4) + 1)
          * ((long)(area.maxZ() >> 4) - (area.minZ() >> 4) + 1);
      if (count > limit) throw oversized(count, limit);
      for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++)
        for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
          chunks.add(new ChunkPos(x, z));
          if (chunks.size() > limit) throw oversized(chunks.size(), limit);
        }
    }
    j.footprint = List.copyOf(areas);
    j.plan = chunks;
    j.total = chunks.size();
  }

  private static IllegalArgumentException oversized(long count, int limit) {
    return new IllegalArgumentException("建筑扫描区域过大：" + count + " 个去重区块，服务端上限 " + limit);
  }

  static boolean contains(Job j, BlockPos p) {
    // 保留手工测试夹具仅设置region的兼容性，真实任务一定有完整分段投影。
    if (j.footprint.isEmpty()) return j.region.isInside(p);
    for (var area : j.footprint) if (area.isInside(p)) return true;
    return false;
  }

  static boolean contains(Job j, Object o) {
    return contains(j, SourceExtras.pos(o));
  }

  static String structureKey(Job j) {
    return j.dimension + "|" + j.result.getString("Structure") + "|" + j.start.toLong();
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

  static void stopForMagnet(ServerPlayer p, ItemStack magnet, String reason) {
    var j = JOBS.get(p.getUUID());
    if (j == null || !j.magnet.equals(Data.id(magnet))) return;
    if (!j.complete) AutoStructures.remember(p, j.result, "BLOCKED", reason);
    cancel(p.getUUID());
  }

  static CompoundTag taskSnapshot(ServerPlayer p, ItemStack magnet) {
    var t = new CompoundTag();
    var j = JOBS.get(p.getUUID());
    if (j == null || !j.magnet.equals(Data.id(magnet))) {
      var result = snapshot(p);
      if (AutoStructures.resultValid(p, result)) {
        String state = AutoStructures.state(p, result);
        t.putString("Name", result.getString("Structure"));
        t.putString("Status", state.equals("COMPLETE") ? "此结构已完成，可手动重新扫描"
            : state.equals("BLOCKED") ? "自动任务已停止，请手动继续" : "等待靠近最新结构目标");
        t.putBoolean("CanContinue", state.equals("BLOCKED"));
      } else t.putString("Status", "等待有效的指南针结构搜索结果");
      return t;
    }
    int ready = 0;
    for (var at : j.waiting) if (ready(j, at)) ready++;
    for (var at : j.tickets.keySet()) if (ready(j, at)) ready++;
    t.putString("Name", j.result.getString("Structure"));
    t.putString("Status", j.status);
    t.putBoolean("Running", j.running || j.preparing);
    t.putBoolean("Paused", j.paused);
    t.putBoolean("Auto", j.automatic);
    t.putBoolean("WaitingTarget", j.waitingTarget);
    t.putBoolean("CanContinue", j.paused && !j.complete);
    t.putInt("Done", j.done); t.putInt("Total", j.total);
    t.putInt("Ready", ready);
    t.putInt("Waiting", Math.max(0, j.waiting.size() + j.tickets.size() - ready));
    t.putInt("Found", j.found); t.putInt("Processed", j.processed); t.putInt("Skipped", j.skipped);
    return t;
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
              case "UNSUPPORTED" -> "当前指南针未提供可用建筑结果，请使用受支持的建筑指南针重新搜索";
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
      if (old != null && old.magnet.equals(Data.id(item)) && valid(p, old)) {
        old.viewing = true;
        if (old.body == null) { tell(p, "建筑布局正在加载，请稍后打开"); return; }
        PlanPreview.reset(old);
        send(p, old, true);
        return;
      }
      cancel(p.getUUID());
      try {
        var j = create(p, item, result, false);
        if (j.structure == null) throw new IllegalArgumentException("建筑注册信息已失效，请重新搜索");
        geometry(j);
        if (!near(p, j.body)) throw new IllegalArgumentException("请靠近建筑：水平及垂直距离均需不超过32格");
        j.previewY = Math.max(j.region.minY(), Math.min(j.region.maxY(), p.blockPosition().getY()));
        PlanPreview.reset(j);
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
      if (!j.complete) AutoStructures.remember(p, j.result, "BLOCKED", "玩家取消任务");
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
      PlanPreview.request(p, j, req.action(), req.value());
      return;
    }
    if (req.action() == 21 || req.action() == 24) {
      try { resume(p, j, item(p, j)); }
      catch (IllegalArgumentException ex) { tell(p, ex.getMessage()); }
    }
  }

  static void resume(ServerPlayer p, Job j, ItemStack magnet) {
    if (magnet.isEmpty()) throw new IllegalArgumentException("对应磁铁已经不再可用");
    if (j.complete) {
      j.complete = j.running = false;
      j.done = j.found = j.loadRequests = j.processed = j.skipped = 0;
      j.seen.clear(); j.skippedKeys.clear(); j.markers.clear(); j.waiting.clear();
    }
    Faults.structure(structureKey(j));
    try { Faults.migrate(p, magnet); } finally { Faults.clearContext(); }
    if (Data.tag(magnet).contains("Recovery")) {
      j.paused = true;
      j.status = "先在磁铁设置中处理恢复缓存，再继续收取";
      return;
    }
    if (!j.running) geometry(j);
    if (!near(p, j.body)) throw new IllegalArgumentException("请靠近建筑：水平及垂直距离均需不超过32格");
    if (!j.level.getWorldBorder().isWithinBounds(new BlockPos(j.region.minX(), 0, j.region.minZ()))
        || !j.level.getWorldBorder().isWithinBounds(new BlockPos(j.region.maxX(), 0, j.region.maxZ())))
      throw new IllegalArgumentException("扫描区域超出世界边界，无法完整收取");
    if (!j.running) {
      var ordered = new ArrayList<>(j.plan);
      var origin = p.chunkPosition();
      ordered.sort(Comparator.comparingLong(at -> chunkDistance(at, origin)));
      j.waiting.addAll(ordered);
      j.running = true;
    }
    j.preparing = j.paused = j.waitingTarget = false;
    j.waitStartedNanos = 0;
    j.waitSample = ItemStack.EMPTY;
    j.status = "加载并收取中";
    Data.tag(magnet).putBoolean("Enabled", true);
    AutoStructures.allow(p, j.result);
    send(p, j, false);
  }

  static void previewRequest(ServerPlayer p, Net.PreviewRequest req) {
    var j = JOBS.get(p.getUUID());
    if (j == null || !j.viewing || !j.nonce.equals(req.nonce()) || !valid(p, j)) return;
    var held = held(p);
    if (!held.is(ChestMagnet.MAGNET.get()) || !Data.id(held).equals(j.magnet)) return;
    PlanPreview.request(p, j, req.revision(), req.centerX(), req.centerZ(), req.height());
  }

  static boolean valid(ServerPlayer p, Job j) {
    var h = item(p, j);
    var s = snapshot(p);
    return p.isAlive()
        && !p.isSpectator()
        && p.serverLevel() == j.level
        && h.is(ChestMagnet.MAGNET.get())
        && Data.id(h).equals(j.magnet)
        && s.getString("State").equals("FOUND")
        && s.hasUUID("Revision")
        && s.getUUID("Revision").equals(j.revision)
        && (j.preparing ? AutoStructures.nearAnchor(p, j.result) : j.body != null && near(p, j.body));
  }

  static void send(ServerPlayer p, Job j, boolean reset) {
    if (p.connection == null || !j.viewing || j.body == null) return;
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
    t.putInt("Mode", Data.mode(item(p, j)));
    t.putInt("Done", j.done);
    t.putInt("Total", j.total);
    t.putInt("Found", j.found);
    t.putInt("Processed", j.processed);
    int ready = 0;
    for (var at : j.waiting) if (ready(j, at)) ready++;
    for (var at : j.tickets.keySet()) if (ready(j, at)) ready++;
    t.putInt("Ready", ready);
    t.putInt("Waiting", Math.max(0, j.waiting.size() + j.tickets.size() - ready));
    t.putInt("Skipped", j.skipped);
    t.putInt("LoadRequests", j.loadRequests);
    t.putLongArray("ScanChunks", j.plan.stream().mapToLong(ChunkPos::toLong).toArray());
    t.putBoolean("Running", j.running);
    t.putBoolean("Paused", j.paused);
    t.putBoolean("Reset", reset);
    t.putBoolean("Open", reset && j.viewing);
    t.putInt("Slice", j.previewY);
    PlanPreview.snapshot(j, t);
    Net.structure(p, t);
  }

  public static void tick(net.minecraft.server.MinecraftServer server, long deadline) {
    var jobs = List.copyOf(JOBS.entrySet());
    int first = jobs.isEmpty() ? 0 : Math.floorMod(jobCursor++, jobs.size());
    for (int visit = 0; visit < jobs.size(); visit++) {
      var entry = jobs.get((first + visit) % jobs.size());
      var p = server.getPlayerList().getPlayer(entry.getKey());
      var j = entry.getValue();
      if (p == null || !valid(p, j) || j.running && !Data.enabled(item(p, j))) {
        cancel(entry.getKey());
        if (p != null) p.displayClientMessage(Component.literal("结构任务已停止：目标、距离或磁铁状态发生变化"), false);
        continue;
      }
      try {
        Faults.structure(structureKey(j));
        long slice = Math.min(deadline, System.nanoTime()
            + Math.max(100_000L, (deadline - System.nanoTime()) / (jobs.size() - visit)));
        var magnet = item(p, j);
        if (j.preparing) prepareAutomatic(p, j, magnet);
        var target = Destinations.deferred(() -> Destinations.get(p, Data.mode(magnet)));
        // 目标满/离线只有限期低频重试；真正异常不会进入此分支。
        if (j.waitingTarget) {
          if (serviceTargetWait(j, () -> target,
              j.level.getGameTime(), System.nanoTime())) {
            j.status = "目的地连续15秒不可接收，已停止自动重试；请手动继续";
            AutoStructures.remember(p, j.result, "BLOCKED", j.status);
            p.displayClientMessage(Component.literal(j.status), false);
          }
        }
        if (j.running && !j.paused) collect(p, j, slice, target);
        if (j.viewing && j.body != null && System.nanoTime() < slice) preview(p, j, slice);
        if (j.level.getGameTime() % 20 == 0) send(p, j, false);
        if (p.containerMenu instanceof MagnetMenu menu && menu.identity.equals(j.magnet)
            && j.level.getGameTime() % 20 == 0) Net.sync(p, magnet, j.status);
      } catch (Exception ex) {
        var magnet = item(p, j);
        Faults.report(p, magnet, ex);
        releaseForPause(j);
        j.paused = true; j.waitingTarget = j.preparing = false;
        j.status = Faults.count(magnet, structureKey(j)) > 3
            ? "当前结构已多次出错，建议放弃；仍可继续并跳过错误来源"
            : "任务已暂停；再次点击继续会跳过错误来源";
        AutoStructures.remember(p, j.result, "BLOCKED", j.status);
        send(p, j, false);
      } finally {
        Faults.clearContext();
      }
      if (System.nanoTime() >= deadline) break;
    }
  }

  static void prepareAutomatic(ServerPlayer p, Job j, ItemStack magnet) {
    if (full(j, j.start)) {
      geometry(j);
      AutoStructures.layout(p, j);
      release(j);
      if (!near(p, j.body)) {
        cancel(p.getUUID());
        return;
      }
      j.preparing = false;
      resume(p, j, magnet);
      return;
    }
    if (j.level.getGameTime() - j.created > 600)
      throw new IllegalArgumentException("建筑起点加载超时，未开始吸收");
    acquire(j, j.start, j.level.getGameTime());
  }

  static void pauseTarget(ServerPlayer p, Job j, String reason) {
    pauseTarget(p, j, reason, ItemStack.EMPTY);
  }

  static void pauseTarget(ServerPlayer p, Job j, String reason, ItemStack sample) {
    j.status = reason;
    j.paused = true;
    if (j.automatic) {
      if (!sample.isEmpty()) j.waitSample = sample.copy();
      j.waitingTarget = true;
      if (j.waitStartedNanos == 0) j.waitStartedNanos = System.nanoTime();
      j.nextTargetCheck = j.level.getGameTime() + 20;
      j.status += " · 等待恢复（最多15秒）";
    }
    releaseForPause(j);
  }

  static boolean targetWaitExpired(Job j, long nowNanos) {
    return j.waitStartedNanos != 0 && nowNanos - j.waitStartedNanos >= 15_000_000_000L;
  }

  /** 返回true只表示已超时；注入时钟使测试不必真实等待十五秒。 */
  static boolean serviceTargetWait(Job j, java.util.function.Supplier<Destinations.Target> target,
      long tick, long nowNanos) {
    if (targetWaitExpired(j, nowNanos)) {
      j.waitingTarget = false; j.paused = true;
      return true;
    }
    if (tick < j.nextTargetCheck) return false;
    j.nextTargetCheck = tick + 20;
    // 拒收期间只重查目的地，不能为探测容量重新加载来源区块。
    if (canReceive(target.get(), j.waitSample)) {
      j.paused = j.waitingTarget = false;
      j.waitStartedNanos = 0; j.waitSample = ItemStack.EMPTY;
    }
    return false;
  }

  static boolean canReceive(Destinations.Target target, ItemStack sample) {
    return !target.unavailable() && (sample.isEmpty()
        || target.insert(sample, true).getCount() < sample.getCount());
  }

  static void preview(ServerPlayer p, Job j, long deadline) {
    if (j.viewing) PlanPreview.tick(p, j, deadline);
  }

  static long chunkDistance(ChunkPos first, ChunkPos second) {
    long dx = (long) first.x - second.x, dz = (long) first.z - second.z;
    return dx * dx + dz * dz;
  }

  private static boolean full(Job j, ChunkPos at) {
    var chunk = j.level.getChunkSource().getChunkNow(at.x, at.z);
    return chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL);
  }

  private static boolean ready(Job j, ChunkPos at) {
    return full(j, at) && j.level.areEntitiesLoaded(at.toLong());
  }

  /** 热区块保活也计入2/4租约上限，但不冒充新的加载请求或等待5tick。 */
  private static boolean acquire(Job j, ChunkPos at, long now) {
    if (j.tickets.containsKey(at)) return true;
    if (j.tickets.size() >= 2 || ticketCount() >= 4) return false;
    boolean cold = !full(j, at);
    if (cold && now - j.lastRequest < 5) return false;
    j.waiting.remove(at);
    j.level.getChunkSource().addRegionTicket(TICKET, at, 0, j.nonce);
    j.tickets.put(at, now);
    if (cold) {
      j.lastRequest = now;
      j.loadRequests++;
    }
    return true;
  }

  private static ChunkPos closest(Job j, ChunkPos origin, boolean requireReady) {
    ChunkPos best = null;
    long distance = Long.MAX_VALUE;
    for (var at : j.waiting) {
      if (requireReady && !ready(j, at)) continue;
      long d = chunkDistance(at, origin);
      if (d < distance) { best = at; distance = d; }
    }
    return best;
  }

  private static void prefetch(Job j, ChunkPos origin, long now) {
    var next = j.resume != null ? j.resume : closest(j, origin, false);
    if (next != null) acquire(j, next, now);
  }

  static boolean doubleReady(ServerPlayer p, Job j, Object o) {
    BlockPos other;
    if (o instanceof net.minecraft.world.level.block.entity.BlockEntity be
        && SourceExtras.blockId(p, be.getBlockPos()).equals("tetra:forged_container"))
      other = SourceExtras.tetraOther(p, be.getBlockPos());
    else if (o instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
      var state = chest.getBlockState();
      if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
          || state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
              == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return true;
      other = chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
    } else return true;
    if (other == null || !contains(j, other)) return true;
    var cp = new ChunkPos(other);
    if (ready(j, cp)) return true;
    // 成对库存优先于预取；只释放非当前的自有租约，不触碰其他模组票据。
    if (!j.tickets.containsKey(cp)) {
      for (var at : List.copyOf(j.tickets.keySet()))
        if (!at.equals(j.current)) {
          j.level.getChunkSource().removeRegionTicket(TICKET, at, 0, j.nonce);
          j.tickets.remove(at);
          if (!j.waiting.contains(at)) j.waiting.addFirst(at);
        }
      acquire(j, cp, j.level.getGameTime());
    }
    return false;
  }

  private static boolean selectCurrent(ServerPlayer p, Job j, ItemStack magnet, long now) {
    if (j.current != null) return true;
    if (j.resume != null) {
      if (!acquire(j, j.resume, now)) return false;
      if (!ready(j, j.resume)) return false;
      j.current = j.resume;
      j.resume = null;
    } else {
      // 已就绪的等待区块直接进入处理，不经过新加载发单节流。
      var hot = closest(j, p.chunkPosition(), true);
      if (hot != null && acquire(j, hot, now)) j.current = hot;
      if (j.current == null)
        for (var at : j.tickets.keySet())
          if (ready(j, at)) { j.current = at; break; }
      if (j.current == null) {
        prefetch(j, p.chunkPosition(), now);
        return false;
      }
    }
    var chunk = j.level.getChunkSource().getChunkNow(j.current.x, j.current.z);
    j.specialScan = SourceExtras.option(magnet, "CollectLocked") ? new SourceExtras.Scan(chunk) : null;
    Faults.context(p, magnet, null, "发现来源");
    j.blockEntities = new ArrayList<>(chunk.getBlockEntitiesPos());
    j.blockCursor = j.entityCursor = 0;
    var area = new AABB(j.current.getMinBlockX(), j.region.minY(), j.current.getMinBlockZ(),
        j.current.getMaxBlockX() + 1, j.region.maxY() + 1, j.current.getMaxBlockZ() + 1);
    // 原生范围查询取得候选身份；不跨tick保留实体或库存引用。
    j.entityIds = j.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, area, Sources::eligible)
        .stream().map(net.minecraft.world.entity.Entity::getUUID).toList();
    return true;
  }

  private static int discoverCurrent(ServerPlayer p, Job j, ItemStack magnet, int budget, long deadline) {
    int visited = 0;
    while (j.blockCursor < j.blockEntities.size() && visited < budget && System.nanoTime() < deadline) {
      visited++;
      var at = j.blockEntities.get(j.blockCursor++);
      if (!contains(j, at)) continue;
      var be = j.level.getBlockEntity(at);
      if (Sources.eligible(be)) addSource(p, j, magnet, Sources.of(be), at);
    }
    while (j.entityCursor < j.entityIds.size() && visited < budget && System.nanoTime() < deadline) {
      visited++;
      var entity = j.level.getEntity(j.entityIds.get(j.entityCursor++));
      if (entity != null && entity.isAlive() && Sources.eligible(entity) && contains(j, entity))
        addSource(p, j, magnet, Sources.of(entity), entity.blockPosition());
    }
    return visited;
  }

  private static boolean discoveryComplete(Job j) {
    return j.blockCursor >= j.blockEntities.size() && j.entityCursor >= j.entityIds.size();
  }

  private static void addSource(ServerPlayer p, Job j, ItemStack magnet, Sources.Ref ref, BlockPos at) {
    if (Faults.skipped(p, magnet, ref)) {
      if (j.skippedKeys.add(ref.key(p))) j.skipped++;
      return;
    }
    if (!j.seen.add(ref.key(p))) return;
    j.sources.add(ref);
    j.found++;
    if (j.markers.size() < 2048) j.markers.add(at.asLong());
  }

  static void collect(ServerPlayer p, Job j, long deadline) {
    collect(p, j, deadline, Destinations.deferred(() -> Destinations.get(p, Data.mode(item(p, j)))));
  }

  private static void collect(ServerPlayer p, Job j, long deadline, Destinations.Target target) {
    long now = j.level.getGameTime();
    var magnet = item(p, j);
    if (magnet.isEmpty()) return;
    Faults.migrate(p, magnet);
    if (!Data.enabled(magnet)) {
      j.status = "旧版异常缓存已隔离；请再次开启并继续";
      j.paused = true;
      releaseForPause(j);
      return;
    }
    Faults.context(p, magnet, null, "准备目的地");
    if (LootCapture.pending(magnet) && target.unavailable()) {
      pauseTarget(p, j, target.reason());
      return;
    }
    Faults.context(p, magnet, null, "接收待处理产物");
    boolean hadPending = LootCapture.pending(magnet);
    int pendingBefore = pendingCount(magnet);
    if (!LootCapture.drain(magnet, target, deadline)) {
      j.processed += Math.max(0, pendingBefore - pendingCount(magnet));
      if (System.nanoTime() >= deadline) return;
      var first = Data.tag(magnet).getList("PendingLoot", 10).getCompound(0);
      var sample = ItemStack.of(first);
      if (!sample.isEmpty() && target.insert(sample, true).getCount() < sample.getCount()) return;
      pauseTarget(p, j, "开箱产物待接收 · " + target.reason(), sample);
      return;
    }
    if (hadPending) {
      j.processed += pendingBefore;
      j.waitingTarget = false; j.waitStartedNanos = 0;
      return;
    }
    for (var entry : j.tickets.entrySet())
      if (!ready(j, entry.getKey()) && now - entry.getValue() > 600) {
        j.status = "区块加载超时，未全部完成；可继续";
        j.paused = true;
        releaseForPause(j);
        AutoStructures.remember(p, j.result, "BLOCKED", j.status);
        return;
      }
    int probes = 0, moved = 0, discoveries = 0;
    // 共享同一tick的槽/栈预算；空热区块最多连续完成8块，避免独占其他玩家时间片。
    for (int chunks = 0; chunks < 8 && System.nanoTime() < deadline; chunks++) {
      Faults.context(p, magnet, null, "准备区块");
      if (!selectCurrent(p, j, magnet, now)) break;
      prefetch(j, j.current, now);
      Faults.context(p, magnet, null, "发现来源");
      discoveries += discoverCurrent(p, j, magnet, Config.SCAN.get() - discoveries, deadline);
      // 同一时间片可完成一个特殊分节，不再每512格强制等待下一tick。
      for (int batch = 0; j.specialScan != null && batch < 8 && System.nanoTime() < deadline; batch++)
        if (j.specialScan.step(o -> {
          if (contains(j, SourceExtras.pos(o))) addSource(p, j, magnet, Sources.of(o), SourceExtras.pos(o));
        }, deadline)) j.specialScan = null;
      while (!j.sources.isEmpty() && probes < Config.PROBES.get()
          && moved < Config.STACKS.get() && System.nanoTime() < deadline) {
        var ref = j.sources.peek();
        Faults.context(p, magnet, ref, "准备");
        var o = ref.resolve(p);
        if (Faults.skipped(p, magnet, ref) && j.skippedKeys.add(ref.key(p))) j.skipped++;
        if (o == null || !contains(j, o) || Data.excluded(magnet, ref.key(p))
            || Faults.skipped(p, magnet, ref)
            || ref.members(p).stream().anyMatch(at -> !contains(j, at))) {
          j.sources.remove(); j.slot = 0; continue;
        }
        if (!doubleReady(p, j, o)) return;
        if (target.unavailable()) { pauseTarget(p, j, target.reason()); return; }
        if (target.loops(ref, p)) { j.sources.remove(); j.slot = 0; continue; }
        var inv = Sources.inventory(p, o, magnet);
        if (inv == null) { j.sources.remove(); j.slot = 0; continue; }
        // 开锁/粉碎已生成的奖励下tick统一交付，不能同tick叠加另一份栈预算。
        if (LootCapture.pending(magnet)) { j.status = "开箱产物待处理"; return; }
        if (j.slot >= inv.getSlots()) {
          Faults.context(p, magnet, ref, "收尾");
          boolean finished = SourceExtras.finish(p, o, inv, magnet);
          if (LootCapture.pending(magnet)) { j.status = "收尾产物待处理"; return; }
          if (!finished) {
            j.status = "来源仍有余量或开箱产物未接收";
            j.paused = true; releaseForPause(j); return;
          }
          j.sources.remove(); j.slot = 0; continue;
        }
        Faults.context(p, magnet, ref, "提取");
        probes++;
        var sample = inv.extractItem(j.slot, 64, true);
        if (sample.isEmpty()) { j.slot++; continue; }
        int accepted = sample.getCount() - target.insert(sample, true).getCount();
        if (accepted <= 0) {
          pauseTarget(p, j, target.reason(), sample); return;
        }
        j.waitingTarget = false; j.waitStartedNanos = 0;
        int n = Transfer.move(inv, j.slot, accepted, target, magnet);
        j.processed += n;
        if (n > 0) moved++; else j.slot++;
        if (Data.tag(magnet).contains("Recovery")) {
          j.status = "转移余量已进入恢复缓存，任务暂停";
          j.paused = true; releaseForPause(j); return;
        }
      }
      if (!discoveryComplete(j) || !j.sources.isEmpty() || j.specialScan != null || LootCapture.pending(magnet)) break;
      var at = j.current;
      if (j.tickets.remove(at) != null)
        j.level.getChunkSource().removeRegionTicket(TICKET, at, 0, j.nonce);
      j.current = null;
      j.blockEntities = List.of();
      j.entityIds = List.of();
      j.done++;
      if (probes >= Config.PROBES.get() || moved >= Config.STACKS.get()) break;
    }
    if (j.current == null && j.tickets.isEmpty() && j.waiting.isEmpty() && !j.paused) {
      j.complete = true;
      int skipped = j.skipped;
      int isolated = Math.max(0, Data.tag(magnet).getList("Quarantined", 10).size() - j.isolatedBefore);
      j.status = skipped > 0 || isolated > 0
          ? "扫描结束，已跳过 " + skipped + " 个错误来源、保留 " + isolated + " 条隔离记录 · 已处理 " + j.processed + " 件"
          : "全部扫描收取完成 · 已处理 " + j.processed + " 件";
      j.paused = true;
      j.waitingTarget = false;
      AutoStructures.remember(p, j.result, skipped == 0 && isolated == 0 ? "COMPLETE" : "BLOCKED", j.status);
      if (j.automatic && skipped == 0 && isolated == 0) {
        String key = j.result.getString("Structure");
        p.displayClientMessage(Component.literal("当前结构：")
            .append(Component.translatableWithFallback("structure." + key.replace(':', '.'), key))
            .append(" 已吸收完毕"), false);
      } else p.displayClientMessage(Component.literal(j.status), false);
      send(p, j, false);
      if (!j.viewing) cancel(p.getUUID());
    }
  }

  static int pendingCount(ItemStack magnet) {
    int count = 0;
    for (var entry : Data.tag(magnet).getList("PendingLoot", 10))
      count += ItemStack.of((CompoundTag) entry).getCount();
    return count;
  }

  static void releaseForPause(Job j) {
    for (var at : j.tickets.keySet())
      if (!at.equals(j.current) && !j.waiting.contains(at)) j.waiting.addFirst(at);
    release(j);
    if (j.current != null) {
      j.resume = j.current;
      if (!j.waiting.contains(j.current)) j.waiting.addFirst(j.current);
      j.current = null;
    }
    // 保留来源与槽位，重新就绪后补扫特殊分节，不持有已释放区块的引用。
    j.specialScan = null;
    j.blockEntities = List.of();
    j.entityIds = List.of();
    j.blockCursor = j.entityCursor = 0;
  }
}
