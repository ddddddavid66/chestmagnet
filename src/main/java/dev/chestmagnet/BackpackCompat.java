package dev.chestmagnet;

import java.util.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

public final class BackpackCompat {
  private static final Set<String> VANILLA_GROUPS = Set.of("main", "armor", "offhand");
  private static final MethodHandle ENUMERATE = resolveEnumerator();
  private record Bag(ItemStack stack, String group, String identifier, int slot) {}

  private static MethodHandle resolveEnumerator() {
    var arguments = MethodType.methodType(void.class, Player.class,
        PlayerInventoryProvider.BackpackInventorySlotConsumer.class);
    var lookup = MethodHandles.publicLookup();
    try {
      // 旧版返回 void；新版的 boolean 不影响本模组的全量枚举。
      return lookup.findVirtual(PlayerInventoryProvider.class, "runOnBackpacks",
          arguments.changeReturnType(boolean.class)).asType(arguments.insertParameterTypes(0, PlayerInventoryProvider.class));
    } catch (NoSuchMethodException oldVersion) {
      try {
        return lookup.findVirtual(PlayerInventoryProvider.class, "runOnBackpacks", arguments);
      } catch (ReflectiveOperationException incompatible) {
        throw new IllegalStateException("精妙背包的随身槽位接口不兼容", incompatible);
      }
    } catch (IllegalAccessException incompatible) {
      throw new IllegalStateException("无法访问精妙背包的随身槽位接口", incompatible);
    }
  }

  private static void enumerate(Player player, PlayerInventoryProvider.BackpackInventorySlotConsumer consumer) {
    try {
      ENUMERATE.invokeExact(PlayerInventoryProvider.get(), player, consumer);
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new IllegalStateException("无法枚举精妙背包槽位", failure);
    }
  }

  private static int priority(String g) {
    return switch (g) {
      case "curios" -> 0;
      case "armor" -> 1;
      case "offhand" -> 3;
      case "main" -> 4;
      default -> 2;
    };
  }

  static List<IStorageWrapper> wrappers(ServerPlayer p) {
    List<Bag> bags = new ArrayList<>();
    enumerate(
            p,
            (s, g, id, slot) -> {
              if (!VANILLA_GROUPS.contains(g))
                bags.add(new Bag(s, g, id, slot));
              return false;
            });
    bags.add(new Bag(p.getItemBySlot(EquipmentSlot.CHEST), "armor", "", 0));
    bags.add(new Bag(p.getOffhandItem(), "offhand", "", 0));
    for (int slot = 0; slot < 36; slot++)
      bags.add(new Bag(p.getInventory().getItem(slot), "main", "", slot));
    bags.sort(
        Comparator.comparingInt((Bag b) -> priority(b.group))
            .thenComparing(Bag::identifier)
            .thenComparingInt(Bag::slot));
    List<IStorageWrapper> wrappers = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    Set<ItemStack> stacks = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var b : bags) {
      if (b.stack.isEmpty() || !stacks.add(b.stack)) continue;
      if (!(b.stack.getItem() instanceof net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem)) continue;
      var wrapper = BackpackWrapper.fromStack(b.stack);
      if (wrapper.getContentsUuid().isEmpty() || seen.add(wrapper.getContentsUuid().get()))
        wrappers.add(wrapper);
    }
    return wrappers;
  }

  public static Destinations.Target target(ServerPlayer p) {
    var wrappers = wrappers(p);
    if (wrappers.isEmpty()) return Destinations.failed("未携带可用背包，请切换物品栏/RS");
    return new Destinations.Target() {
      public ItemStack insert(ItemStack s, boolean simulate) {
        var left = s.copy();
        for (var w : wrappers) {
          if (left.isEmpty()) break;
          // EMPTY 余量可能代表销毁或压缩，按背包自身规则消费，不补发这些物品。
          left = w.getInventoryForInputOutput().insertItem(left, simulate);
        }
        return left;
      }
    };
  }
}
