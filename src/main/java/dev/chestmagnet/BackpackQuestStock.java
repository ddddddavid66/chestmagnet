package dev.chestmagnet;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.inception.InceptionUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

/** 读真实槽位。利用精妙核心每次保存时替换的 inventory 标签验证分批结果。 */
final class BackpackQuestStock implements QuestStock.Reader {
  private final ServerPlayer player;
  private final List<Bag> bags = new ArrayList<>();
  private final Set<UUID> seen = new HashSet<>();
  private final Set<IStorageWrapper> seenWrappers = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<UUID> roots = new HashSet<>();
  private final boolean allowInception = allowsInception();
  private int bagIndex;
  private long amount;

  private static final class Bag {
    final IStorageWrapper wrapper;
    final UUID uuid;
    Object revision;
    int slot, slots;
    boolean opened, inception;
    Bag(IStorageWrapper wrapper, UUID uuid) { this.wrapper = wrapper; this.uuid = uuid; }
  }

  BackpackQuestStock(ServerPlayer player) {
    this.player = player;
    for (var wrapper : BackpackCompat.wrappers(player))
      wrapper.getContentsUuid().ifPresent(id -> {
        roots.add(id);
        add(wrapper, id);
      });
  }

  private static boolean allowsInception() {
    return net.p3pp3rf1y.sophisticatedbackpacks.Config.SERVER.inceptionUpgrade
        .upgradesUseInventoriesOfBackpacksInBackpack.get();
  }

  private void add(IStorageWrapper wrapper, UUID id) {
    if (seen.add(id) && seenWrappers.add(wrapper)) bags.add(new Bag(wrapper, id));
  }

  private static boolean nested(IStorageWrapper wrapper) {
    return wrapper.getUpgradeHandler().getSlotWrappers().values().stream()
        .anyMatch(upgrade -> upgrade instanceof InceptionUpgradeWrapper && upgrade.isEnabled());
  }

  private static Object revision(UUID id) {
    return BackpackStorage.get().getOrCreateBackpackContents(id).get("inventory");
  }

  public boolean step(QuestMatch match, long limit, long deadline) {
    int budget = 64;
    while (bagIndex < bags.size() && amount < limit && budget-- > 0 && System.nanoTime() < deadline) {
      var bag = bags.get(bagIndex);
      var inventory = bag.wrapper.getInventoryHandler();
      if (!bag.opened) {
        bag.opened = true;
        bag.revision = revision(bag.uuid);
        bag.inception = allowInception && nested(bag.wrapper);
        bag.slots = inventory.getSlots();
      }
      if (bag.slot >= bag.slots) { bagIndex++; continue; }
      var stack = inventory.getStackInSlot(bag.slot++);
      if (stack.isEmpty()) continue;
      if (bag.inception) BackpackWrapper.fromExistingData(stack)
          .ifPresent(w -> w.getContentsUuid().ifPresent(id -> add(w, id)));
      if (match.candidates().contains(stack.getItem()) && match.matches().test(stack))
        amount += Math.min(limit - amount, stack.getCount());
    }
    return bagIndex >= bags.size() || amount >= limit;
  }

  public long amount() { return amount; }

  public boolean valid() {
    if (allowInception != allowsInception()) return false;
    var current = new HashSet<UUID>();
    for (var wrapper : BackpackCompat.wrappers(player)) wrapper.getContentsUuid().ifPresent(current::add);
    if (!roots.equals(current)) return false;
    for (var bag : bags) {
      if (bag.opened && (revision(bag.uuid) != bag.revision
          || bag.wrapper.getInventoryHandler().getSlots() != bag.slots
          || (allowInception && nested(bag.wrapper)) != bag.inception)) return false;
    }
    return true;
  }
}
