package dev.chestmagnet;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

public final class BackpackCompat {
  private static final Set<String> VANILLA_GROUPS = Set.of("main", "armor", "offhand");
  private record Bag(ItemStack stack, String group, String identifier, int slot) {}

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
    PlayerInventoryProvider.get()
        .runOnBackpacks(
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
      b.stack
          .getCapability(CapabilityBackpackWrapper.getCapabilityInstance())
          .ifPresent(
              w -> {
                if (w.getContentsUuid().isEmpty() || seen.add(w.getContentsUuid().get()))
                  wrappers.add(w);
              });
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
          if (simulate || !BackpackReceipt.active()) {
            left = w.getInventoryForInputOutput().insertItem(left, simulate);
          } else {
            BackpackReceipt.bag(w, w.getInventoryHandler());
            try { left = w.getInventoryForInputOutput().insertItem(left, false); }
            finally { BackpackReceipt.endBag(); }
          }
        }
        return left;
      }
    };
  }
}
