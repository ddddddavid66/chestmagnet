package dev.rsqa124;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.block.BaseBlock;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.WirelessTransmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;

/** 正式客户端跨维度：使用真实玩家、原生绑定、普通附近调度及原生无线网格。 */
final class Production125Rs {
  static volatile boolean openGrid;
  static volatile boolean gridVerified;
  private static INetwork network;
  private static WirelessTransmitterBlockEntity access;
  private static BarrelBlockEntity source;
  private static IEnergyStorage battery;
  private static int stage, age, before;
  private static final BlockPos CONTROLLER = new BlockPos(0, 120, 0);

  static void tick(ServerPlayer p) {
    if (++age > 360) throw new IllegalStateException("RS125 timed out, stage " + stage);
    if (stage == 0) {
      var level = p.serverLevel();
      p.getInventory().clearContent();
      p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      p.teleportTo(0.5, 120, 2.5);
      level.setChunkForced(0, 0, true);
      level.setBlockAndUpdate(CONTROLLER, block("controller").defaultBlockState());
      level.setBlockAndUpdate(CONTROLLER.east(), block("1k_storage_block").defaultBlockState());
      var wireless = (BaseBlock) block("wireless_transmitter");
      level.setBlockAndUpdate(CONTROLLER.above(),
          wireless.defaultBlockState().setValue(wireless.getDirection().getProperty(), Direction.DOWN));
      network = ((ControllerBlockEntity) level.getBlockEntity(CONTROLLER)).getNetwork();
      access = (WirelessTransmitterBlockEntity) level.getBlockEntity(CONTROLLER.above());
      network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
      network.getNodeGraph().invalidate(Action.PERFORM, level, CONTROLLER);
      stage++;
      return;
    }
    network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    if (stage == 1) {
      if (!network.canRun() || access.getNode().getNetwork() != network
          || network.getItemStorageCache().getStorages().isEmpty()) return;
      var terminal = item("refinedstorageaddons:wireless_crafting_grid");
      p.setItemInHand(InteractionHand.MAIN_HAND, terminal);
      var hit = new BlockHitResult(Vec3.atCenterOf(CONTROLLER), Direction.UP, CONTROLLER, false);
      Production125Compat.require(terminal.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit))
          .consumesAction(), "wireless crafting terminal native binding");
      battery = terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new);
      battery.receiveEnergy(Integer.MAX_VALUE, false);
      before = battery.getEnergyStored();
      var nether = p.getServer().getLevel(Level.NETHER);
      var stand = new BlockPos(8, 120, 8);
      nether.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
      nether.setBlockAndUpdate(stand, Blocks.AIR.defaultBlockState());
      nether.setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
      p.teleportTo(nether, 8.5, 120, 8.5, 0, 0);
      nether.setBlockAndUpdate(stand.east(), Blocks.BARREL.defaultBlockState());
      source = (BarrelBlockEntity) nether.getBlockEntity(stand.east());
      source.setItem(0, new ItemStack(Items.EMERALD, 17));
      var magnet = item("chestmagnet:chest_magnet");
      magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 1);
      p.getInventory().setItem(1, magnet);
      stage++;
      age = 0;
      return;
    }
    if (stage == 2 && age >= 30) {
      Production125Compat.require(source.getItem(0).getCount() == 17 && battery.getEnergyStored() == before,
          "cross-dimension no card kept source and charge");
      access.getNode().getUpgrades().setStackInSlot(0, item("rsinfinitybooster:dimension_card"));
      stage++;
      return;
    }
    if (stage == 3 && source.isEmpty()) {
      int stored = network.getItemStorageCache().getList().getStacks().stream()
          .filter(entry -> entry.getStack().is(Items.EMERALD))
          .mapToInt(entry -> entry.getStack().getCount()).sum();
      Production125Compat.require(stored == 17 && battery.getEnergyStored()
          == before - RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage(), "exact items and fee");
      Production125Compat.require(p.containerMenu == p.inventoryMenu, "magnet never opens GUI");
      openGrid = true;
      stage++;
    }
    if (stage == 4 && gridVerified) {
      p.getServer().overworld().setChunkForced(0, 0, false);
      NearbyProductionChecks.LOG.info("QA125_RS_PASS Nether barrel -> Overworld storage: 17, one fee, native client grid");
      NearbyProductionChecks.done = true;
    }
  }

  private static net.minecraft.world.level.block.Block block(String id) {
    return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", id));
  }

  private static ItemStack item(String id) {
    return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)));
  }
}
