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
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.*;

public final class Net {
  private static final SimpleChannel CHANNEL =
      NetworkRegistry.newSimpleChannel(
          new ResourceLocation(ChestMagnet.ID, "main"), () -> "9", "9"::equals, "9"::equals);
  static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(ForgeRegistries.MENU_TYPES, ChestMagnet.ID);
  public static final RegistryObject<MenuType<MagnetMenu>> MENU =
      MENUS.register(
          "settings",
          () ->
              IForgeMenuType.create(
                  (id, inv, buf) -> new MagnetMenu(id, inv, buf.readBoolean(), buf.readUUID())));

  public record Request(int action, int value, UUID identity) {}

  public record RecentPage(UUID identity, int menuId, int request, CompoundTag data) {}

  public record Snapshot(CompoundTag data, String status, int maxRadius) {}
  public record PreviewRequest(UUID nonce, int revision, int centerX, int centerZ, int height) {}

  // C2S 只接受有界的标识与摘要，不接收任意物品标签。
  record TrackingEdit(UUID identity, int menu, int operation, int sequence, int row,
      String item, byte kind, String variant, String name, String label, String enchants) {}
  record TrackingList(UUID identity, int menu, CompoundTag data, String note) {}
  record TrackingAlert(String rule, CompoundTag display, int amount, boolean destroyed) {}

  public static void init() {
    CHANNEL.registerMessage(6, TrackingEdit.class, (m, b) -> {
      b.writeUUID(m.identity()); b.writeVarInt(m.menu()); b.writeByte(m.operation());
      b.writeInt(m.sequence()); b.writeVarInt(m.row()); b.writeUtf(m.item(), 256);
      b.writeByte(m.kind()); b.writeUtf(m.variant(), 64); b.writeUtf(m.name(), 128);
      b.writeUtf(m.label(), 96); b.writeUtf(m.enchants(), 512);
    }, b -> new TrackingEdit(b.readUUID(), b.readVarInt(), b.readByte(), b.readInt(), b.readVarInt(),
      b.readUtf(256), b.readByte(), b.readUtf(64), b.readUtf(128), b.readUtf(96), b.readUtf(512)),
      (m, c) -> {
        var ctx = c.get(); ctx.enqueueWork(() -> handleTracking(ctx.getSender(), m));
        ctx.setPacketHandled(true);
      }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    CHANNEL.registerMessage(7, TrackingList.class, (m, b) -> {
      b.writeUUID(m.identity()); b.writeVarInt(m.menu()); b.writeNbt(m.data()); b.writeUtf(m.note(), 128);
    }, b -> new TrackingList(b.readUUID(), b.readVarInt(), b.readNbt(), b.readUtf(128)),
      (m, c) -> {
        var ctx = c.get(); ctx.enqueueWork(() -> MagnetScreen.receiveTracking(m));
        ctx.setPacketHandled(true);
      }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(8, TrackingAlert.class, (m, b) -> {
      b.writeUtf(m.rule(), 330); b.writeNbt(m.display()); b.writeVarInt(m.amount()); b.writeBoolean(m.destroyed());
    }, b -> new TrackingAlert(b.readUtf(330), b.readNbt(), b.readVarInt(), b.readBoolean()),
      (m, c) -> {
        var ctx = c.get(); ctx.enqueueWork(() -> TrackingToast.receive(m));
        ctx.setPacketHandled(true);
      }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    MENUS.register(FMLJavaModLoadingContext.get().getModEventBus());
    CHANNEL.registerMessage(5, RecentPage.class,
        (m, b) -> { b.writeUUID(m.identity()); b.writeVarInt(m.menuId()); b.writeInt(m.request()); b.writeNbt(m.data()); },
        b -> new RecentPage(b.readUUID(), b.readVarInt(), b.readInt(), b.readNbt()),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> MagnetScreen.receiveRecent(m));
          ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(4, PreviewRequest.class,
        (m, b) -> { b.writeUUID(m.nonce()); b.writeInt(m.revision()); b.writeInt(m.centerX());
          b.writeInt(m.centerZ()); b.writeInt(m.height()); },
        b -> new PreviewRequest(b.readUUID(), b.readInt(), b.readInt(), b.readInt(), b.readInt()),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> {
            var p = ctx.getSender();
            if (p != null) QUEUED_PREVIEWS.put(p.getUUID(), m);
          });
          ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    CHANNEL.registerMessage(
        3, Faults.Report.class,
        (m, b) -> { b.writeUUID(m.id()); b.writeUUID(m.magnet()); b.writeUtf(m.text(), 24000); },
        b -> new Faults.Report(b.readUUID(), b.readUUID(), b.readUtf(24000)),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> ErrorScreen.receive(m));
          ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(
        2,
        CompoundTag.class,
        (m, b) -> b.writeNbt(m),
        b -> b.readNbt(),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> StructureScreen.receive(m));
          ctx.setPacketHandled(true);
        },
        Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    CHANNEL.registerMessage(
        0,
        Request.class,
        (m, b) -> {
          b.writeInt(m.action);
          b.writeInt(m.value);
          b.writeUUID(m.identity);
        },
        b -> new Request(b.readInt(), b.readInt(), b.readUUID()),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> handle(ctx.getSender(), m));
          ctx.setPacketHandled(true);
        },
        Optional.of(NetworkDirection.PLAY_TO_SERVER));
    CHANNEL.registerMessage(
        1,
        Snapshot.class,
        (m, b) -> {
          b.writeNbt(m.data);
          b.writeUtf(m.status, 512);
          b.writeInt(m.maxRadius);
        },
        b -> new Snapshot(b.readNbt(), b.readUtf(512), b.readInt()),
        (m, c) -> {
          var ctx = c.get();
          ctx.enqueueWork(() -> ClientEvents.snapshot(m));
          ctx.setPacketHandled(true);
        },
        Optional.of(NetworkDirection.PLAY_TO_CLIENT));
  }

  static void trackingAlert(ServerPlayer p, TrackingAlert message) {
    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), message);
  }

  static void trackingEdit(TrackingEdit edit) { CHANNEL.sendToServer(edit); }

  private static void handleTracking(ServerPlayer p, TrackingEdit action) {
    if (p == null || !(p.containerMenu instanceof MagnetMenu menu) || menu.containerId != action.menu()
        || !menu.identity.equals(action.identity()) || !menu.stillValid(p)
        || !menu.allowTracking(p.server.getTickCount())) return;
    String note = "";
    switch (action.operation()) {
      case 0 -> {}
      case 1, 3 -> {
        var key = new Tracking.Key(action.item(), action.kind(), action.variant());
        var rule = action.operation() == 3
            ? Tracking.rules(p).stream().filter(r -> r.key().equals(key)).findFirst().orElse(null)
            : new Tracking.Rule(key, display(action));
        if (!Tracking.toggle(p, rule)) note = "无法修改追踪：物品变体无效或列表已满";
      }
      case 2 -> {
        if (!Tracking.toggle(p, menu.historyRule(action.sequence(), action.row())))
          note = "无法从此条历史追踪；请刷新或选择具体物品";
      }
      default -> { return; }
    }
    var rows = new ListTag();
    Tracking.rules(p).forEach(rule -> rows.add(rule.save()));
    var data = new CompoundTag();
    data.put("Rows", rows);
    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new TrackingList(menu.identity, menu.containerId, data, note));
  }

  private static CompoundTag display(TrackingEdit action) {
    var result = new CompoundTag();
    result.putString("Item", action.item());
    if (!action.name().isBlank()) result.putString("Name", action.name());
    if (!action.label().isBlank()) result.putString("Variant", action.label());
    var enchantments = new ListTag();
    for (var line : action.enchants().split("\n", 17)) {
      int split = line.lastIndexOf('=');
      if (split < 1 || enchantments.size() >= 16) continue;
      var id = ResourceLocation.tryParse(line.substring(0, split));
      if (id == null || !ForgeRegistries.ENCHANTMENTS.containsKey(id)) continue;
      try {
        int level = Integer.parseInt(line.substring(split + 1));
        if (level < 1 || level > 32767) continue;
        var enchantment = new CompoundTag();
        enchantment.putString("id", id.toString());
        enchantment.putInt("lvl", level);
        enchantments.add(enchantment);
      } catch (NumberFormatException ignored) {}
    }
    if (!enchantments.isEmpty()) result.put("Enchants", enchantments);
    return result;
  }

  public static void structure(ServerPlayer p, CompoundTag data) {
    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), data);
  }

  public static void sendReport(ServerPlayer p, Faults.Report report) {
    // 限制网络正文；完整异常由服务端报告保留，客户端只保存本玩家的副本。
    String text = report.text();
    if (text.length() > 24000) text = text.substring(0, 23800) + "\n[客户端显示已截断；完整内容见服务端日志]";
    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
        new Faults.Report(report.id(), report.magnet(), text));
  }

  public static void request(int action, int value, UUID id) {
    CHANNEL.sendToServer(new Request(action, value, id));
  }

  public static void previewRequest(UUID nonce, int revision, int x, int z, int y) {
    CHANNEL.sendToServer(new PreviewRequest(nonce, revision, x, z, y));
  }

  public static void sync(ServerPlayer p, ItemStack s, String status) {
    var data = Data.tag(s).copy();
    for (String key : List.of("Quarantined", "PendingLoot", "Recovery", "OperationEvidence",
        "UncertainBatch", "FaultCounts", "PendingOrigin")) data.remove(key);
    // 菜单只需要是否存在安全缓存，不需要其中物品，更不能发送隔离批次的原始NBT。
    if (Data.tag(s).contains("Recovery")) data.put("Recovery", new CompoundTag());
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
    CHANNEL.send(
        PacketDistributor.PLAYER.with(() -> p),
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
    if (q.action() == 27) {
      if (!(p.containerMenu instanceof MagnetMenu menu) || !menu.stillValid(p)
          || !menu.identity.equals(q.identity()) || !allowAction(p)) return;
      var magnet = menu.stack();
      Data.tag(magnet).putBoolean("ProtectBoss", !BossProtection.enabled(magnet));
      p.getInventory().setChanged();
      sync(p, magnet, Engine.status(p));
      return;
    }
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
        Data.tag(item).putBoolean("AutoStructure", !Data.autoStructure(item));
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
      NetworkHooks.openScreen(
          p,
          new SimpleMenuProvider(
              (n, inv, player) -> new MagnetMenu(n, inv, off, id), Component.literal("宝藏磁铁")),
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
      var history = RecentItems.page(p, category, page, true);
      var rows = history.getList("Rows", Tag.TAG_COMPOUND);
      menu.offerHistory(q.value, rows);
      for (int i = 0; i < rows.size(); i++) {
        var row = rows.getCompound(i);
        row.put("Display", RecentItems.publicDisplay(row.getCompound("Display")));
      }
      CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
          new RecentPage(menu.identity, menu.containerId, q.value, history));
      return;
    }
    var s = menu.stack();
    var t = Data.tag(s);
    switch (q.action) {
      case 1 -> t.putInt("Mode", Data.normalizeMode(q.value));
      case 18 -> {
        t.putBoolean("QuestDetection", !Data.questDetection(t));
        QuestDetection.remove(p.getUUID());
      }
      case 2 -> Engine.toggle(p, s);
      case 3, 4, 5 ->
          t.putInt(
              new String[] {"X", "Y", "Z"}[q.action - 3],
              Math.max(0, Math.min(Config.RADIUS.get(), q.value)));
      case 6 -> {
        var list = Data.exclusions(s);
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
          var stack = ItemStack.of(t.getCompound("Recovery"));
          Faults.context(p, s, null, "取回安全恢复缓存");
          t.putBoolean("RecoveryUncertain", true);
          try {
            var left = Destinations.get(p, 0).insert(stack, false);
            t.remove("RecoveryUncertain");
            if (left.isEmpty()) t.remove("Recovery");
            else t.put("Recovery", left.save(new CompoundTag()));
          } catch (Exception ex) {
            t.putBoolean("Enabled", false);
            Faults.report(p, s, ex);
          } finally { Faults.clearContext(); }
        }
      }
      default -> {
        return;
      }
    }
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
    double blockReach = Math.max(0, p.getAttributeValue(ForgeMod.BLOCK_REACH.get()));
    double entityReach = Math.max(0, p.getAttributeValue(ForgeMod.ENTITY_REACH.get()));
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
