package dev.chestmagnet;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.*;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.node.ExternalStorageNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.WirelessTransmitterNetworkNode;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.util.NetworkUtils;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

public final class RsCompat {
  private static final Set<String> IDS =
      Set.of(
          "refinedstorage:wireless_grid",
          "refinedstorage:creative_wireless_grid",
          "refinedstorageaddons:wireless_crafting_grid",
          "refinedstorageaddons:creative_wireless_crafting_grid");

  static boolean covers(ServerPlayer player, INetworkNode node) {
    if (!node.isActive() || !(node instanceof IWirelessTransmitter transmitter)) return false;
    // 调用真实 getRange，保留无限卡等扩展注入；坐标与原生 NetworkItemManager 一致。
    if (transmitter.getDimension().equals(player.level().dimension())) {
      var origin = transmitter.getOrigin();
      double range = transmitter.getRange();
      if (range > 0
          && player.distanceToSqr(origin.getX(), origin.getY(), origin.getZ()) < range * range)
        return true;
    }
    // 仅安装了维度卡的已激活原生发送器可跨维度，强加载本身不等于无线授权。
    return node instanceof WirelessTransmitterNetworkNode wireless
        && BoosterCompat.crossDimension(wireless);
  }

  public static Destinations.Target target(ServerPlayer p) {
    String error = "未携带已绑定无线网格，请切换背包/物品栏";
    for (int i = 0; i < 37; i++) {
      ItemStack s = i == 36 ? p.getOffhandItem() : p.getInventory().getItem(i);
      String id = String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem()));
      if (!IDS.contains(id) || !(s.getItem() instanceof NetworkItem item)) continue;
      INetwork[] found = {null};
      String[] failure = {"终端未绑定或控制器区块未加载"};
      item.applyNetwork(p.server, s, n -> found[0] = n, c -> failure[0] = c.getString());
      var n = found[0];
      if (n == null) {
        error = failure[0];
        continue;
      }
      if (!n.canRun()) {
        error = "RS 网络离线";
        continue;
      }
      boolean range;
      try {
        range =
            n.getNodeGraph().all().stream()
                .map(INetworkNodeGraphEntry::getNode)
                .anyMatch(node -> covers(p, node));
      } catch (IllegalStateException e) {
        // 兼容查询失败属于目的地不可用；尚未提取，不应把当前容器隔离成错误来源。
        error = e.getMessage();
        continue;
      }
      if (!range) {
        error = "超出 RS 无线覆盖范围";
        continue;
      }
      if (!n.getSecurityManager().hasPermission(Permission.INSERT, p)) {
        error = "没有 RS 存入权限";
        continue;
      }
      boolean useEnergy =
          id.startsWith("refinedstorageaddons:")
              ? com.refinedmods.refinedstorageaddons.RSAddons.SERVER_CONFIG
                  .getWirelessCraftingGrid()
                  .getUseEnergy()
              : RS.SERVER_CONFIG.getWirelessGrid().getUseEnergy();
      // Addons 合成网格继承原版 ItemGridHandler：插入费仍读取 RS 的 getInsertUsage。
      // 它自己的配置只决定是否耗电，不能误用 craftUsage/openUsage 代替插入费。
      int fee =
          id.contains("creative") || !useEnergy
              ? 0
              : RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage();
      var energy = s.getCapability(ForgeCapabilities.ENERGY).orElse(null);
      if (fee > 0 && (energy == null || energy.extractEnergy(fee, true) < fee)) {
        error = "RS 无线网格电量不足";
        continue;
      }
      Set<Object> storages = Collections.newSetFromMap(new IdentityHashMap<>());
      storages.addAll(n.getItemStorageCache().getStorages());
      return new Destinations.Target() {
        public ItemStack insert(ItemStack stack, boolean simulate) {
          if (fee > 0 && energy.extractEnergy(fee, true) < fee) return stack;
          var left =
              n.insertItem(stack, stack.getCount(), simulate ? Action.SIMULATE : Action.PERFORM);
          if (!simulate && left.getCount() < stack.getCount() && fee > 0)
            energy.extractEnergy(fee, false);
          return left;
        }

        public boolean loops(Sources.Ref source, ServerPlayer player) {
          for (var at : source.members(player))
            for (var d : Direction.values()) {
              var pos = at.relative(d);
              if (!player.level().hasChunkAt(pos)) continue;
              var be = player.level().getBlockEntity(pos);
              if (be == null) continue;
              var node = NetworkUtils.getNodeFromBlockEntity(be);
              if (node instanceof ExternalStorageNetworkNode ext
                  && ext.getNetwork() == n
                  && ext.getFacingBlockEntity() != null
                  && source.members(player).contains(ext.getFacingBlockEntity().getBlockPos())
                  && ext.getItemStorages().stream().anyMatch(storages::contains)) return true;
            }
          return false;
        }

        public String reason() {
          return "RS 已满、过滤拒收或终端电量不足，请切换背包/物品栏";
        }
      };
    }
    return Destinations.failed(error + "；可切换背包/物品栏");
  }
}
