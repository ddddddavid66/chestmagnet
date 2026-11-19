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

final class TrackingBags {
  static void deliver(ServerPlayer p) throws ReflectiveOperationException {
    toggle(p, new ItemStack(Items.PAPER));
    var bagA = item("sophisticatedbackpacks:backpack");
    var bagB = item("sophisticatedbackpacks:backpack");
    p.getInventory().setItem(12, bagA);
    p.getInventory().setItem(13, bagB);
    var cap = CapabilityBackpackWrapper.getCapabilityInstance();
    var first = bagA.getCapability(cap).orElseThrow(IllegalStateException::new);
    var second = bagB.getCapability(cap).orElseThrow(IllegalStateException::new);
    var inventory = first.getInventoryHandler();
    for (int i = 0; i < inventory.getSlots(); i++) inventory.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
    inventory.setStackInSlot(0, new ItemStack(Items.PAPER, 63));
    second.getClass().getMethod("setContentsUuid", UUID.class).invoke(second, UUID.randomUUID());
    second.getInventoryHandler();
    second.getUpgradeHandler().setStackInSlot(0, item("sophisticatedbackpacks:void_upgrade"));
    var voider = second.getUpgradeHandler().getWrappersThatImplement(VoidUpgradeWrapper.class).get(0);
    try {
      var type = Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType");
      voider.getClass().getMethod("setVoidType", type).invoke(voider, type.getField("ALWAYS").get(null));
    } catch (ClassNotFoundException legacy) {
      voider.getClass().getMethod("setShouldVoidOverflow", boolean.class).invoke(voider, false);
    }
    voider.getFilterLogic().setEmptyAllowListMatchesEverything();
    require(target(p, 2).insert(new ItemStack(Items.PAPER, 3), true).isEmpty(), "simulate bag behavior");
    require(target(p, 2).insert(new ItemStack(Items.PAPER, 3), false).isEmpty(), "real bags accepted all");
    require(inventory.getStackInSlot(0).getCount() == 64 && second.getInventoryHandler().getStackInSlot(0).isEmpty(),
        "native one stored and two vanished");
    // 正式压缩升级将 9 块铁变为铁块；原始铁锭不应收到获取或销毁弹窗。
    p.getInventory().setItem(12, ItemStack.EMPTY);
    p.getInventory().setItem(13, ItemStack.EMPTY);
    var compactBag = item("sophisticatedbackpacks:backpack");
    p.getInventory().setItem(12, compactBag);
    var compact = compactBag.getCapability(cap).orElseThrow(IllegalStateException::new);
    compact.getClass().getMethod("setContentsUuid", UUID.class).invoke(compact, UUID.randomUUID());
    compact.getInventoryHandler();
    compact.getUpgradeHandler().setStackInSlot(0, item("sophisticatedbackpacks:advanced_compacting_upgrade"));
    var upgrade = compact.getUpgradeHandler().getWrappersThatImplement(
        net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeWrapper.class).get(0);
    upgrade.getFilterLogic().setEmptyAllowListMatchesEverything();
    upgrade.setCompactNonUncraftable(true);
    upgrade.setShouldWorkdInGUI(true);
    toggle(p, new ItemStack(Items.IRON_INGOT));
    require(target(p, 2).insert(new ItemStack(Items.IRON_INGOT, 9), false).isEmpty(), "native compact transfer");
    int blocks = 0;
    for (int i = 0; i < compact.getInventoryHandler().getSlots(); i++) {
      var present = compact.getInventoryHandler().getStackInSlot(i);
      require(!present.is(Items.IRON_INGOT), "native compacted original");
      if (present.is(Items.IRON_BLOCK)) blocks += present.getCount();
    }
    require(blocks == 1, "native compact produced iron block");
    System.out.println("TRACK_COMPACT_NATIVE_PASS");
  }
}
