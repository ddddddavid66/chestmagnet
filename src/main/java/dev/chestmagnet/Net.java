package dev.chestmagnet;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.*;

public final class Net {
  static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ChestMagnet.ID);
  public static final DeferredHolder<MenuType<?>, MenuType<MagnetMenu>> MENU =
      MENUS.register("settings",
          () -> IMenuTypeExtension.create((id, inv, buf) -> new MagnetMenu(id, inv, buf.readBoolean(), buf.readUUID())));

  private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
    return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ChestMagnet.ID, path));
  }

  public record Request(int action, int value, UUID identity) implements CustomPacketPayload {
    static final Type<Request> TYPE = Net.type("action");
    public Type<Request> type() { return TYPE; }
  }

  public record RecentPage(UUID identity, int menuId, int request, CompoundTag data) implements CustomPacketPayload {
    static final Type<RecentPage> TYPE = Net.type("recent");
    public Type<RecentPage> type() { return TYPE; }
  }

  public record Snapshot(CompoundTag data, String status, int maxRadius) implements CustomPacketPayload {
    static final Type<Snapshot> TYPE = Net.type("settings");
    public Type<Snapshot> type() { return TYPE; }
  }

  public record PreviewRequest(UUID nonce, int revision, int centerX, int centerZ, int height) implements CustomPacketPayload {
    static final Type<PreviewRequest> TYPE = Net.type("preview_request");
    public Type<PreviewRequest> type() { return TYPE; }
  }

  private record StructureData(CompoundTag data) implements CustomPacketPayload {
    static final Type<StructureData> TYPE = Net.type("structure");
    public Type<StructureData> type() { return TYPE; }
  }

  private record ReportData(Faults.Report report) implements CustomPacketPayload {
    static final Type<ReportData> TYPE = Net.type("error");
    public Type<ReportData> type() { return TYPE; }
  }

  public static void init(IEventBus bus) {
    MENUS.register(bus);
    bus.addListener(Net::registerPayloads);
  }

  private static void registerPayloads(RegisterPayloadHandlersEvent event) {
    // 协议独立于模组版本；旧 Forge 客户端不能混用。
    var registrar = event.registrar("9");
    registrar.playToServer(Request.TYPE, StreamCodec.<RegistryFriendlyByteBuf, Request>of(
        (b, m) -> { b.writeInt(m.action()); b.writeInt(m.value()); b.writeUUID(m.identity()); },
        b -> new Request(b.readInt(), b.readInt(), b.readUUID())),
        (m, c) -> { if (c.player() instanceof ServerPlayer p) handle(p, m); });
    registrar.playToServer(PreviewRequest.TYPE, StreamCodec.<RegistryFriendlyByteBuf, PreviewRequest>of(
        (b, m) -> { b.writeUUID(m.nonce()); b.writeInt(m.revision()); b.writeInt(m.centerX());
          b.writeInt(m.centerZ()); b.writeInt(m.height()); },
        b -> new PreviewRequest(b.readUUID(), b.readInt(), b.readInt(), b.readInt(), b.readInt())),
        (m, c) -> { if (c.player() instanceof ServerPlayer p) QUEUED_PREVIEWS.put(p.getUUID(), m); });
    registrar.playToClient(RecentPage.TYPE, StreamCodec.<RegistryFriendlyByteBuf, RecentPage>of(
        (b, m) -> { b.writeUUID(m.identity()); b.writeVarInt(m.menuId()); b.writeInt(m.request()); b.writeNbt(m.data()); },
        b -> new RecentPage(b.readUUID(), b.readVarInt(), b.readInt(), b.readNbt())),
        (m, c) -> MagnetScreen.receiveRecent(m));
    registrar.playToClient(Snapshot.TYPE, StreamCodec.<RegistryFriendlyByteBuf, Snapshot>of(
        (b, m) -> { b.writeNbt(m.data()); b.writeUtf(m.status(), 512); b.writeInt(m.maxRadius()); },
        b -> new Snapshot(b.readNbt(), b.readUtf(512), b.readInt())),
        (m, c) -> ClientEvents.snapshot(m));
    registrar.playToClient(StructureData.TYPE, StreamCodec.<RegistryFriendlyByteBuf, StructureData>of(
        (b, m) -> b.writeNbt(m.data()), b -> new StructureData(b.readNbt())),
        (m, c) -> StructureScreen.receive(m.data()));
    registrar.playToClient(ReportData.TYPE, StreamCodec.<RegistryFriendlyByteBuf, ReportData>of(
        (b, m) -> { b.writeUUID(m.report().id()); b.writeUUID(m.report().magnet()); b.writeUtf(m.report().text(), 24000); },
        b -> new ReportData(new Faults.Report(b.readUUID(), b.readUUID(), b.readUtf(24000)))),
        (m, c) -> ErrorScreen.receive(m.report()));
  }

  public static void structure(ServerPlayer p, CompoundTag data) {
    PacketDistributor.sendToPlayer(p, new StructureData(data));
  }

  public static void sendReport(ServerPlayer p, Faults.Report report) {
    // 限制网络正文；完整异常由服务端报告保留，客户端只保存本玩家的副本。
    String text = report.text();
    if (text.length() > 24000) text = text.substring(0, 23800) + "\n[客户端显示已截断；完整内容见服务端日志]";
    PacketDistributor.sendToPlayer(p, new ReportData(new Faults.Report(report.id(), report.magnet(), text)));
  }

  public static void request(int action, int value, UUID id) {
    PacketDistributor.sendToServer(new Request(action, value, id));
  }

  public static void previewRequest(UUID nonce, int revision, int x, int z, int y) {
    PacketDistributor.sendToServer(new PreviewRequest(nonce, revision, x, z, y));
  }

  public static void sync(ServerPlayer p, ItemStack s, String status) {
    var data = Data.tag(s).copy();
    for (String key : List.of("Quarantined", "PendingLoot", "Recovery", "OperationEvidence",
        "UncertainBatch", "FaultCounts", "PendingOrigin")) data.remove(key);
    // 菜单只需要是否存在安全缓存，不需要其中物品，更不能发送隔离批次的原始NBT。
    if (Data.tag(s).contains("Recovery")) data.put("Recovery", new CompoundTag());
    data.putInt("QuarantineCount", Data.tag(s).getList("Quarantined", 10).size());
    var original = Data.tag(s).getList("FaultSkipped", 10);
    var visible = new ListTag();
    for (int i = 0; i < Math.min(256, original.size()); i++) {
      var entry = new CompoundTag();
      var old = original.getCompound(i);
      String label = old.getString("Label");
      entry.putString("Label", label.substring(0, Math.min(256, label.length())));
      if (old.hasUUID("Report")) entry.putUUID("Report", old.getUUID("Report"));
      visible.add(entry);
    }
    data.put("FaultSkipped", visible);
    data.putInt("FaultSkippedTotal", original.size());
    data.put("StructureTask", StructureJobs.taskSnapshot(p, s));
    PacketDistributor.sendToPlayer(p,
        new Snapshot(data, status, Config.RADIUS.get()));
  }

  private static final Map<UUID, Long> OPENED = new HashMap<>();
  private static final Map<UUID, Long> ACTIONS = new HashMap<>();
  private static final Map<UUID, Long> PREVIEWS = new HashMap<>();
  private static final Map<UUID, PreviewRequest> QUEUED_PREVIEWS = new HashMap<>();

  public static void clearPlayer(UUID id) {
    OPENED.remove(id);
    ACTIONS.remove(id);
    PREVIEWS.remove(id);
    QUEUED_PREVIEWS.remove(id);
  }

  public static void clearAll() {
    OPENED.clear();
    ACTIONS.clear();
    PREVIEWS.clear();
    QUEUED_PREVIEWS.clear();
  }

  private static void handle(ServerPlayer p, Request q) {
    if (p == null || !p.isAlive() || p.isSpectator()) return;
    if (q.action() >= 20) {
      if (q.action() == 20) {
        long now = p.level().getGameTime();
        if (now - OPENED.getOrDefault(p.getUUID(), -100L) < 10) return;
        OPENED.put(p.getUUID(), now);
      }
      StructureJobs.request(p, q);
      return;
    }
    if (q.action == 11 || q.action == 12) {
      var item = held(p, q.identity, true);
      if (item.isEmpty() || !allowAction(p)) return;
      if (q.action == 11) Engine.toggle(p, item);
      else {
        var aim = aim(p);
        if (Sources.eligible(aim.source())) Engine.exclude(p, item, aim.source());
      }
      return;
    }
    if (q.action >= 13 && q.action <= 17) {
      var item = held(p, q.identity, false);
      if (item.isEmpty() && p.containerMenu instanceof MagnetMenu menu
          && menu.stillValid(p) && menu.identity.equals(q.identity)) item = menu.stack();
      if (item.isEmpty() || !allowAction(p)) return;
      if (q.action == 13) Faults.clearSkip(item, q.value);
      if (q.action == 15) {
        boolean auto = !Data.autoStructure(item);
        Data.edit(item, data -> data.putBoolean("AutoStructure", auto));
        if (!Data.autoStructure(item)) StructureJobs.stopForMagnet(p, item, "玩家关闭自动结构收取");
      }
      if (q.action == 16 || q.action == 17) {
        try { StructureJobs.control(p, item, q.action == 17); }
        catch (IllegalArgumentException ex) { p.displayClientMessage(Component.literal(ex.getMessage()), false); }
      }
      p.getInventory().setChanged();
      sync(p, item, Engine.status(p));
      return;
    }
    if (q.action == 0) {
      long now = p.level().getGameTime();
      if (!aim(p).air() || now - OPENED.getOrDefault(p.getUUID(), -100L) < 10) return;
      boolean off = !p.getMainHandItem().is(ChestMagnet.MAGNET.get());
      var s = held(p, q.identity, true);
      if (s.isEmpty()) return;
      UUID id = Data.id(s);
      OPENED.put(p.getUUID(), now);
      p.openMenu(
          new SimpleMenuProvider(
              (n, inv, player) -> new MagnetMenu(n, inv, off, id), Component.literal("宝箱磁铁")),
          b -> {
            b.writeBoolean(off);
            b.writeUUID(id);
          });
      sync(p, s, Engine.status(p));
      return;
    }
    if (!(p.containerMenu instanceof MagnetMenu menu)
        || !menu.stillValid(p)
        || !menu.identity.equals(q.identity)) return;
    if (q.action == 19) {
      if (!menu.allowRecent(p.server.getTickCount())) return;
      int category = q.value & 7, page = (q.value >>> 3) & 31;
      if (category >= RecentItems.CATEGORIES.length) return;
      PacketDistributor.sendToPlayer(p, new RecentPage(menu.identity, menu.containerId, q.value,
          RecentItems.page(p, category, page)));
      return;
    }
    var s = menu.stack();
    var t = Data.tag(s).copy();
    switch (q.action) {
      case 1 -> t.putInt("Mode", Data.normalizeMode(q.value));
      case 18 -> {
        t.putBoolean("QuestDetection", !Data.questDetection(t));
        QuestDetection.remove(p.getUUID());
      }
      case 2 -> { Engine.toggle(p, s); t = Data.tag(s).copy(); }
      case 3, 4, 5 ->
          t.putInt(
              new String[] {"X", "Y", "Z"}[q.action - 3],
              Math.max(0, Math.min(Config.RADIUS.get(), q.value)));
      case 6 -> {
        var list = t.getList("Excluded", Tag.TAG_COMPOUND);
        if (q.value >= 0 && q.value < list.size()) {
          list.remove(q.value);
          t.put("Excluded", list);
        }
      }
      case 9 -> t.putBoolean("CollectLocked", !SourceExtras.option(s, "CollectLocked"));
      case 10 -> t.putBoolean("CollectFrames", !SourceExtras.option(s, "CollectFrames"));
      case 7 -> t.remove("Excluded");
      case 8 -> {
        if (t.contains("Recovery") && !t.getBoolean("RecoveryUncertain")) {
          var stack = StackData.read(p.registryAccess(), t.getCompound("Recovery"));
          if (stack.isEmpty()) {
            Faults.quarantine(s, "Recovery", "无法解析恢复物品，原数据已封存");
            p.displayClientMessage(Component.literal("无法解析恢复物品，原数据已封存"), false);
            t = Data.tag(s).copy();
            break;
          }
          Faults.context(p, s, null, "取回安全恢复缓存");
          t.putBoolean("RecoveryUncertain", true);
          Data.replace(s, t);
          try {
            var left = Destinations.get(p, 0).insert(stack, false);
            t.remove("RecoveryUncertain");
            if (left.isEmpty()) t.remove("Recovery");
            else t.put("Recovery", StackData.write(p.registryAccess(), left));
          } catch (Exception ex) {
            t.putBoolean("Enabled", false);
            Data.replace(s, t);
            Faults.report(p, s, ex);
            t = Data.tag(s).copy();
          } finally { Faults.clearContext(); }
        }
      }
      default -> {
        return;
      }
    }
    Data.replace(s, t);
    p.getInventory().setChanged();
    sync(p, s, Engine.status(p));
  }

  private static boolean allowAction(ServerPlayer p) {
    long now = p.level().getGameTime();
    if (now - ACTIONS.getOrDefault(p.getUUID(), -100L) < 4) return false;
    ACTIONS.put(p.getUUID(), now);
    return true;
  }

  private static boolean allowPreview(ServerPlayer p) {
    long now = p.level().getGameTime();
    if (now - PREVIEWS.getOrDefault(p.getUUID(), -100L) < 5) return false;
    PREVIEWS.put(p.getUUID(), now);
    return true;
  }

  /** 合并快速拖动为最后一个视窗；不能丢掉客户端已经在等待的新revision。 */
  static void flushPreviews(net.minecraft.server.MinecraftServer server) {
    for (var entry : List.copyOf(QUEUED_PREVIEWS.entrySet())) {
      var p = server.getPlayerList().getPlayer(entry.getKey());
      if (p == null) { QUEUED_PREVIEWS.remove(entry.getKey()); continue; }
      if (!allowPreview(p)) continue;
      QUEUED_PREVIEWS.remove(entry.getKey());
      StructureJobs.previewRequest(p, entry.getValue());
    }
  }

  private static ItemStack held(ServerPlayer p, UUID identity, boolean allowUnassigned) {
    var s = StructureJobs.held(p);
    if (!s.is(ChestMagnet.MAGNET.get())) return ItemStack.EMPTY;
    // 初次使用可能尚未收到服务端分配的ID；无UI的即时动作仅允许当前真实手持磁铁。
    if (allowUnassigned && identity.equals(new UUID(0, 0))) return s;
    return Data.id(s).equals(identity) ? s : ItemStack.EMPTY;
  }

  private record Aim(boolean air, Object source) {}

  /** 客户端只发动作，不接受客户端给出的坐标；服务端以真实视线和触及距离重算目标。 */
  private static Aim aim(ServerPlayer p) {
    double blockReach = Math.max(0, p.blockInteractionRange());
    double entityReach = Math.max(0, p.entityInteractionRange());
    var hit = p.pick(blockReach, 0, false);
    var eye = p.getEyePosition();
    var end = eye.add(p.getViewVector(0).scale(entityReach));
    double closest = hit.getType() == HitResult.Type.MISS
        ? entityReach * entityReach : Math.min(entityReach * entityReach, eye.distanceToSqr(hit.getLocation()));
    net.minecraft.world.entity.Entity selected = null;
    var area = p.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
    for (var entity : p.level().getEntities(p, area, e -> e.isAlive() && !e.isSpectator() && e.isPickable())) {
      var box = entity.getBoundingBox().inflate(entity.getPickRadius());
      var point = box.clip(eye, end);
      double distance = box.contains(eye) ? 0 : point.map(eye::distanceToSqr).orElse(Double.MAX_VALUE);
      if (distance < closest) { closest = distance; selected = entity; }
    }
    if (selected != null) return new Aim(false, selected);
    if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
      var at = block.getBlockPos();
      return new Aim(false, SourceExtras.special(p.level().getBlockState(at))
          ? new SourceExtras.Special(at) : p.level().getBlockEntity(at));
    }
    return new Aim(true, null);
  }
}
