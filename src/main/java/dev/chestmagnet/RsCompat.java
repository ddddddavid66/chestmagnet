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
  static final String NO_DIMENSION_CARD =
      "无法跨维度存入：请在此终端绑定的 RS 网络中，给已启用的无线访问点装上「维度卡」。";

  // 无效的后续终端不覆盖已经定位到的网络问题。
  private static final class Failure {
    int stage;
    String message = "未携带已绑定无线网格";

    void at(int next, String detail) {
      if (next <= stage) return;
      stage = next;
      message = detail;
    }
  }
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

  static List<ItemStack> terminals(ServerPlayer player) {
    List<ItemStack> stacks = new ArrayList<>();
    for (int slot = 0; slot < 36; slot++) stacks.add(player.getInventory().getItem(slot));
    stacks.add(player.getOffhandItem());
    // 可选 API 隔离，缺少 Curios 时不加载桥接类。
    if (net.minecraftforge.fml.ModList.get().isLoaded("curios"))
      stacks.addAll(CuriosCompat.terminals(player));
    return stacks;
  }

  public static Destinations.Target target(ServerPlayer p) {
    return target(p, false);
  }

  static Destinations.Target questTarget(ServerPlayer p) {
    return target(p, true);
  }

  private static Destinations.Target target(ServerPlayer p, boolean readOnly) {
    var error = new Failure();
    for (ItemStack s : terminals(p)) {
      String id = String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem()));
      if (!IDS.contains(id) || !(s.getItem() instanceof NetworkItem item)) continue;
      INetwork[] found = {null};
      item.applyNetwork(p.server, s, n -> found[0] = n, ignored -> {});
      var n = found[0];
      if (n == null) {
        if (!NetworkItem.isValid(s) || NetworkItem.getDimension(s) == null) {
          error.at(1, "无线终端未绑定");
          continue;
        }
        var boundLevel = p.server.getLevel(NetworkItem.getDimension(s));
        var boundPos = new net.minecraft.core.BlockPos(
            NetworkItem.getX(s), NetworkItem.getY(s), NetworkItem.getZ(s));
        if (boundLevel == null) error.at(2, "无线终端绑定的维度不可用");
        else if (!boundLevel.hasChunkAt(boundPos)) error.at(2, "RS 控制器所在区块未加载");
        else error.at(2, "在无线终端绑定的位置找不到 RS 网络");
        continue;
      }
      if (!n.canRun()) {
        error.at(3, "RS 网络离线");
        continue;
      }
      boolean crossDimension = !n.getLevel().dimension().equals(p.level().dimension());
      boolean range = false;
      IllegalStateException hookFailure = null;
      try {
        for (var entry : n.getNodeGraph().all()) {
          var node = entry.getNode();
          try {
            if (crossDimension
                ? node.isActive() && node instanceof WirelessTransmitterNetworkNode wireless
                    && BoosterCompat.crossDimension(wireless)
                : covers(p, node)) {
              range = true;
              break;
            }
          } catch (IllegalStateException e) {
            hookFailure = e;
          }
        }
      } catch (IllegalStateException e) {
        hookFailure = e;
      }
      if (!range) {
        error.at(4, hookFailure != null ? hookFailure.getMessage() : crossDimension
            ? (net.minecraftforge.fml.ModList.get().isLoaded("rsinfinitybooster")
                ? NO_DIMENSION_CARD : "未安装 RSInfinityBooster，无法跨维度存入")
            : "超出 RS 无线覆盖范围");
        continue;
      }
      if (!n.getSecurityManager().hasPermission(readOnly ? Permission.EXTRACT : Permission.INSERT, p)) {
        error.at(5, readOnly ? "没有 RS 取出权限" : "没有 RS 存入权限");
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
          readOnly || id.contains("creative") || !useEnergy
              ? 0
              : RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage();
      var energy = s.getCapability(ForgeCapabilities.ENERGY).orElse(null);
      if (fee > 0 && (energy == null || energy.extractEnergy(fee, true) < fee)) {
        error.at(6, "RS 无线网格电量不足");
        continue;
      }
      return new Destinations.Target() {
        private Set<Object> storages;

        public ItemStack insert(ItemStack stack, boolean simulate) {
          if (readOnly) return stack;
          if (fee > 0 && energy.extractEnergy(fee, true) < fee) return stack;
          var left =
              n.insertItem(stack, stack.getCount(), simulate ? Action.SIMULATE : Action.PERFORM);
          if (!simulate && left.getCount() < stack.getCount() && fee > 0)
            energy.extractEnergy(fee, false);
          return left;
        }

        @Override
        public long count(ItemStack key, java.util.function.Predicate<ItemStack> matches,
            long limit, long deadline) {
          if (!n.getSecurityManager().hasPermission(Permission.EXTRACT, p)) return 0;
          long amount = 0;
          int variants = 0;
          for (var entry : n.getItemStorageCache().getList().getStacks(key)) {
            if (amount >= limit || ++variants > 128 || (variants > 4 && System.nanoTime() >= deadline)) break;
            var stack = entry.getStack();
            if (matches.test(stack)) amount += Math.min(limit - amount, stack.getCount());
          }
          return amount;
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
                  && sharesStorage(ext)) return true;
            }
          return false;
        }

        private boolean sharesStorage(ExternalStorageNetworkNode ext) {
          // 仅发现实际外部存储连接时构建防回流集合。
          if (storages == null) {
            storages = Collections.newSetFromMap(new IdentityHashMap<>());
            storages.addAll(n.getItemStorageCache().getStorages());
          }
          return ext.getItemStorages().stream().anyMatch(storages::contains);
        }

        public String reason() {
          return "RS 已满、过滤拒收或终端电量不足，请切换背包/物品栏";
        }
      };
    }
    return Destinations.failed(error.message);
  }
}
