package dev.chestmagnet;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.*;

public final class Net {
  private static final SimpleChannel CHANNEL =
      NetworkRegistry.newSimpleChannel(
          new ResourceLocation(ChestMagnet.ID, "main"), () -> "2", "2"::equals, "2"::equals);
  static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(ForgeRegistries.MENU_TYPES, ChestMagnet.ID);
  public static final RegistryObject<MenuType<MagnetMenu>> MENU =
      MENUS.register(
          "settings",
          () ->
              IForgeMenuType.create(
                  (id, inv, buf) -> new MagnetMenu(id, inv, buf.readBoolean(), buf.readUUID())));

  public record Request(int action, int value, UUID identity) {}

  public record Snapshot(CompoundTag data, String status, int maxRadius) {}

  public static void init() {
    MENUS.register(FMLJavaModLoadingContext.get().getModEventBus());
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

  public static void structure(ServerPlayer p, CompoundTag data) {
    CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), data);
  }

  public static void request(int action, int value, UUID id) {
    CHANNEL.sendToServer(new Request(action, value, id));
  }

  public static void sync(ServerPlayer p, ItemStack s, String status) {
    CHANNEL.send(
        PacketDistributor.PLAYER.with(() -> p),
        new Snapshot(Data.tag(s).copy(), status, Config.RADIUS.get()));
  }

  private static final Map<UUID, Long> OPENED = new HashMap<>();

  public static void clearPlayer(UUID id) {
    OPENED.remove(id);
  }

  public static void clearAll() {
    OPENED.clear();
  }

  private static void handle(ServerPlayer p, Request q) {
    if (p == null || p.isSpectator()) return;
    if (q.action() >= 20) {
      if (q.action() == 20) {
        long now = p.level().getGameTime();
        if (now - OPENED.getOrDefault(p.getUUID(), -100L) < 10) return;
        OPENED.put(p.getUUID(), now);
      }
      StructureJobs.request(p, q);
      return;
    }
    if (q.action == 0) {
      long now = p.level().getGameTime();
      if (!p.isShiftKeyDown() || now - OPENED.getOrDefault(p.getUUID(), -100L) < 10) return;
      boolean off = !p.getMainHandItem().is(ChestMagnet.MAGNET.get());
      var s = off ? p.getOffhandItem() : p.getMainHandItem();
      if (!s.is(ChestMagnet.MAGNET.get())) return;
      UUID id = Data.id(s);
      OPENED.put(p.getUUID(), now);
      NetworkHooks.openScreen(
          p,
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
    var s = menu.stack();
    var t = Data.tag(s);
    switch (q.action) {
      case 1 -> t.putInt("Mode", Math.floorMod(q.value, 3));
      case 2 -> t.putBoolean("Enabled", !Data.enabled(s));
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
      case 7 -> t.remove("Excluded");
      case 8 -> {
        if (t.contains("Recovery") && !t.getBoolean("RecoveryUncertain")) {
          var stack = ItemStack.of(t.getCompound("Recovery"));
          var left = Destinations.get(p, 0).insert(stack, false);
          if (left.isEmpty()) t.remove("Recovery");
          else t.put("Recovery", left.save(new CompoundTag()));
        }
      }
      default -> {
        return;
      }
    }
    p.getInventory().setChanged();
    sync(p, s, Engine.status(p));
  }
}
