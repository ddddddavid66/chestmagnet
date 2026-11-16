package dev.chestmagnet;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.externalstorage.ExternalStorageNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.security.SecurityNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.storage.root.FuzzyRootStorage;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.security.BuiltinPermission;
import com.refinedmods.refinedstorage.common.security.PlayerSecurityActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.slotreference.InventorySlotReferenceFactory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.refinedmods.refinedstorage.common.support.AbstractDirectionalBlock;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import net.neoforged.fml.ModList;

public final class RsCompat {
  private static final Set<String> IDS = Set.of(
      "refinedstorage:wireless_grid", "refinedstorage:creative_wireless_grid",
      "refinedstorage_quartz_arsenal:wireless_crafting_grid",
      "refinedstorage_quartz_arsenal:creative_wireless_crafting_grid");

  private record Terminal(ItemStack stack, int slot) implements SlotReference {
    public boolean isDisabledSlot(int query) { return slot == query; }
    public SlotReferenceFactory getFactory() { return InventorySlotReferenceFactory.INSTANCE; }
    public Optional<ItemStack> resolve(Player player) {
      if (slot >= 0) return player.getInventory().getItem(slot) == stack ? Optional.of(stack) : Optional.empty();
      return player instanceof ServerPlayer p && CuriosCompat.terminals(p).stream().anyMatch(s -> s == stack)
          ? Optional.of(stack) : Optional.empty();
    }
  }

  private record Connection(Network network, NetworkItemContext context, Terminal terminal) {}

  private static List<Terminal> terminals(ServerPlayer player) {
    var choices = new ArrayList<Terminal>();
    for (int slot = 0; slot < 36; slot++) choices.add(new Terminal(player.getInventory().getItem(slot), slot));
    choices.add(new Terminal(player.getOffhandItem(), 40));
    if (ModList.get().isLoaded("curios"))
      for (var stack : CuriosCompat.terminals(player)) choices.add(new Terminal(stack, -1));
    return choices;
  }

  public static Destinations.Target target(ServerPlayer player) { return target(player, false); }

  static Destinations.Target questTarget(ServerPlayer player) { return target(player, true); }

  private static Destinations.Target target(ServerPlayer player, boolean quest) {
    try {
      var api = RefinedStorageApi.INSTANCE;
      var helper = api.getNetworkItemHelper();
      var securityActor = new PlayerSecurityActor(player.getUUID());
      String error = "未携带已绑定 RS 无线网格";
      for (var terminal : terminals(player)) {
        if (terminal.stack.isEmpty() || !IDS.contains(BuiltInRegistries.ITEM.getKey(terminal.stack.getItem()).toString())
            || !helper.isBound(terminal.stack)) continue;
        var context = helper.createContext(terminal.stack, player, terminal);
        if (!context.isActive()) { error = "RS 无线网格没有电量"; continue; }
        // 不使用 force：原生方法检查区块已加载、终端位置和服务端发送器覆盖。
        var resolved = context.resolveNetwork();
        if (resolved.isEmpty()) { error = "RS 网络离线、未加载或无线信号未覆盖当前位置"; continue; }
        var network = resolved.get();
        var security = network.getComponent(SecurityNetworkComponent.class);
        if (!security.isAllowed(BuiltinPermission.OPEN, securityActor)
            || !security.isAllowed(quest ? BuiltinPermission.EXTRACT : BuiltinPermission.INSERT, securityActor)) {
          error = "RS 网络拒绝此玩家访问"; continue;
        }
        return connected(player, new Connection(network, context, terminal));
      }
      return Destinations.failed(error);
    } catch (LinkageError error) {
      return Destinations.failed("RS 2 无线接口不兼容；已停止向该网络存入");
    }
  }

  private static Destinations.Target connected(ServerPlayer player, Connection connection) {
    var storage = connection.network.getComponent(StorageNetworkComponent.class);
    var actor = new PlayerActor(player);
    return new Destinations.Target() {
      private Set<BlockPos> external;

      public ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !connection.context.isActive()) return stack;
        var api = RefinedStorageApi.INSTANCE;
        long cost = api.isEnergyRequired() ? Platform.INSTANCE.getConfig().getWirelessGrid().getInsertEnergyUsage() : 0;
        if (cost > 0 && api.getEnergyStorage(connection.terminal.stack).map(e -> e.getStored() < cost).orElse(false))
          return stack;
        long accepted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(),
            simulate ? Action.SIMULATE : Action.EXECUTE, actor);
        if (accepted < 0 || accepted > stack.getCount())
          throw new IllegalStateException("RS 返回了无效的存入数量");
        if (!simulate && accepted > 0 && cost > 0) connection.context.drainEnergy(cost);
        return stack.copyWithCount(stack.getCount() - (int) accepted);
      }

      public String reason() { return "RS 拒收或无线网格电量不足，请检查网络"; }

      public boolean loops(Sources.Ref source, ServerPlayer p) {
        if (source.pos() == null) return false;
        if (external == null) external = externalPositions(connection.network, p);
        return source.members(p).stream().anyMatch(external::contains);
      }

      public Object stockIdentity() { return storage; }

      public QuestStock.Indexed stock(Set<Item> candidates) {
        if (!(storage instanceof FuzzyRootStorage fuzzy)) return null;
        var revision = new java.util.concurrent.atomic.AtomicLong();
        RootStorageListener listener = change -> revision.incrementAndGet();
        storage.addListener(listener);
        return new QuestStock.Indexed(new QuestStock.View() {
          public Object identity() { return storage; }
          public Object revision() { return revision.get(); }
          public List<? extends QuestStock.Row> rows(Item item) {
            var variants = new ArrayList<ResourceKey>(fuzzy.getFuzzy(new ItemResource(item)));
            return new AbstractList<QuestStock.Row>() {
              public int size() { return variants.size(); }
              public QuestStock.Row get(int i) {
                var variant = variants.get(i);
                return new QuestStock.Row() {
                  public ItemStack stack() { return variant instanceof ItemResource resource ? resource.toItemStack() : ItemStack.EMPTY; }
                  public long count() { return storage.get(variant); }
                };
              }
            };
          }
        }, candidates, () -> storage.removeListener(listener));
      }
    };
  }

  private static Set<BlockPos> externalPositions(Network network, ServerPlayer player) {
    Set<BlockPos> found = new HashSet<>();
    var graph = network.getComponent(GraphNetworkComponent.class);
    for (var container : graph.getContainers(InWorldNetworkNodeContainer.class)) {
      if (!(container.getNode() instanceof ExternalStorageNetworkNode) || container.isRemoved()
          || !player.level().dimension().equals(container.getPosition().dimension())) continue;
      var direction = AbstractDirectionalBlock.tryExtractDirection(container.getBlockState());
      if (direction != null) found.add(container.getLocalPosition().relative(direction));
    }
    return found;
  }
}
