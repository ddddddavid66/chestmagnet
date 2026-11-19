package dev.rsqa124;

import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.chestmagnet.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;

import static dev.rsqa124.TrackingChecks.*;

final class TrackingNetworks {
  private static DimensionsNet beyond;
  static void setup(ServerPlayer p) {
    var level = p.serverLevel();
    level.setBlockAndUpdate(CTRL, ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:controller")).defaultBlockState());
    level.setBlockAndUpdate(CTRL.east(), ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:1k_storage_block")).defaultBlockState());
    var access = (com.refinedmods.refinedstorage.block.BaseBlock) ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:wireless_transmitter"));
    level.setBlockAndUpdate(CTRL.above(), access.defaultBlockState().setValue(access.getDirection().getProperty(), Direction.DOWN));
    var rs = ((ControllerBlockEntity)level.getBlockEntity(CTRL)).getNetwork();
    rs.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    rs.getNodeGraph().invalidate(Action.PERFORM, level, CTRL);
    var terminal = item("refinedstorage:wireless_grid");
    p.setItemInHand(InteractionHand.OFF_HAND, terminal);
    require(terminal.useOn(new UseOnContext(p, InteractionHand.OFF_HAND,
        new BlockHitResult(Vec3.atCenterOf(CTRL), Direction.UP, CTRL, false))).consumesAction(), "RS binding");
    terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new).receiveEnergy(Integer.MAX_VALUE, false);
    beyond = DimensionsNet.createNewNetForPlayer(p, 100_000L, 1000);
    require(beyond != null, "Beyond network");
  }
  static void deliver(ServerPlayer p) throws ReflectiveOperationException {
    var level = p.serverLevel();
    var rs = ((ControllerBlockEntity)level.getBlockEntity(CTRL)).getNetwork();
    toggle(p, new ItemStack(Items.DIAMOND));
    toggle(p, new ItemStack(Items.EMERALD));
    require(target(p, 1).insert(new ItemStack(Items.DIAMOND, 4), true).isEmpty(), "RS simulate");
    require(target(p, 1).insert(new ItemStack(Items.DIAMOND, 4), false).isEmpty(), "RS transfer");
    require(rs.getItemStorageCache().getList().get(new ItemStack(Items.DIAMOND)).getCount() == 4, "RS actual inventory");
    require(target(p, 3).insert(new ItemStack(Items.EMERALD, 5), false).isEmpty(), "Beyond transfer");
    require(beyond.getUnifiedStorage().getStackByKey(new ItemStackKey(new ItemStack(Items.EMERALD))).amount() == 5, "Beyond actual inventory");
  }
}
