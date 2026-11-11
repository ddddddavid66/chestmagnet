package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.node.SecurityManagerNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.WirelessTransmitterNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.storage.StorageNetworkNode;
import com.refinedmods.refinedstorage.block.BaseBlock;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.SecurityManagerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.StorageBlockEntity;
import com.refinedmods.refinedstorage.blockentity.WirelessTransmitterBlockEntity;
import com.refinedmods.refinedstorage.energy.BaseEnergyStorage;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.item.SecurityCardItem;
import com.refinedmods.refinedstorageaddons.RSAddons;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class RsNetworkTests {
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void actualCrossDimensionInsert(GameTestHelper helper) {
    if (!ModList.get().isLoaded("rsinfinitybooster")) {
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info("RS real network integration NOT RUN (Booster absent)");
      helper.succeed();
      return;
    }
    var fixture = new NetworkFixture(helper);
    // 通过真实能量接口持续供电，图重建/节点加载仍走 RS 原生 tick，而不是伪造 INetwork。
    helper.onEachTick(fixture::supplyPower);
    helper
        .startSequence()
        .thenWaitUntil(fixture::assertReady)
        .thenExecute(fixture::verify)
        .thenSucceed();
  }

  private static final class NetworkFixture {
    private final GameTestHelper h;
    private final BlockPos controllerPos;
    private final INetwork network;
    private final WirelessTransmitterNetworkNode transmitter;
    private final StorageNetworkNode storage;
    private final SecurityManagerNetworkNode security;
    private final ServerPlayer binder, remote;
    private boolean feedPower = true;

    NetworkFixture(GameTestHelper helper) {
      h = helper;
      place("controller", new BlockPos(2, 1, 2));
      place("1k_storage_block", new BlockPos(3, 1, 2));
      place("wireless_transmitter", new BlockPos(2, 2, 2));
      place("security_manager", new BlockPos(1, 1, 2));
      controllerPos = h.absolutePos(new BlockPos(2, 1, 2));
      var controller = (ControllerBlockEntity) h.getBlockEntity(new BlockPos(2, 1, 2));
      network = controller.getNetwork();
      transmitter =
          ((WirelessTransmitterBlockEntity) h.getBlockEntity(new BlockPos(2, 2, 2))).getNode();
      storage = ((StorageBlockEntity) h.getBlockEntity(new BlockPos(3, 1, 2))).getNode();
      security = ((SecurityManagerBlockEntity) h.getBlockEntity(new BlockPos(1, 1, 2))).getNode();
      binder = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "RsBind122"));
      var dimension =
          h.getLevel().dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
      var remoteLevel = h.getLevel().getServer().getLevel(dimension);
      h.assertTrue(remoteLevel != null, "second real ServerLevel must exist");
      remote =
          FakePlayerFactory.get(remoteLevel, new GameProfile(UUID.randomUUID(), "RsRemote122"));
      remote.getInventory().clearContent();
      remote.setPos(120, 65, 120);
      binder.setPos(controllerPos.getX(), controllerPos.getY() + 1, controllerPos.getZ());
      transmitter.getUpgrades().setStackInSlot(0, stack("rsinfinitybooster:dimension_card"));
      supplyPower();
      network.getNodeGraph().invalidate(Action.PERFORM, h.getLevel(), controllerPos);
    }

    private void place(String name, BlockPos relative) {
      var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", name));
      h.assertTrue(block != null, "registered RS block " + name);
      var state = block.defaultBlockState();
      if (name.equals("wireless_transmitter"))
        state = state.setValue(((BaseBlock) block).getDirection().getProperty(), Direction.DOWN);
      h.setBlock(relative, state);
    }

    void supplyPower() {
      if (feedPower) network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    }

    void assertReady() {
      h.assertTrue(network.canRun(), "real normal controller powered and loaded");
      h.assertTrue(
          transmitter.getNetwork() == network && transmitter.isActive(),
          "real transmitter connected");
      h.assertTrue(
          storage.getNetwork() == network && security.getNetwork() == network,
          "native graph has storage/security");
      h.assertTrue(
          !network.getItemStorageCache().getStorages().isEmpty(), "native storage cache ready");
      h.assertTrue(
          !remote.level().dimension().equals(network.getLevel().dimension()),
          "player really resides in another dimension");
    }

    void verify() {
      h.assertTrue(
          RS.SERVER_CONFIG.getController().getUseEnergy(),
          "fixture requires real controller energy enabled");
      h.assertTrue(
          RS.SERVER_CONFIG.getWirelessGrid().getUseEnergy(),
          "fixture requires RS terminal energy enabled");
      h.assertTrue(
          RSAddons.SERVER_CONFIG.getWirelessCraftingGrid().getUseEnergy(),
          "fixture requires Addons energy enabled");
      h.assertTrue(
          RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage() > 0,
          "fixture requires nonzero native insert fee");
      terminal("refinedstorage:wireless_grid", Items.DIAMOND, 11);
      terminal("refinedstorageaddons:wireless_crafting_grid", Items.EMERALD, 7);
      curiosTerminal();
      noCard();
      deniedPermission();
      powerFailure();
      h.assertTrue(
          count(Items.DIAMOND) == 11 && count(Items.EMERALD) == 7,
          "only accepted batches reached real storage once");
      h.assertTrue(
          remote.containerMenu == remote.inventoryMenu
              && binder.containerMenu == binder.inventoryMenu,
          "all binding/coverage/insertion paths leave GUI closed");
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info(
              "RS_NETWORK_PASS: real controller/storage/transmitter; dimension={}, ordinary=11"
                  + " diamonds, crafting=7 emeralds; simulation/energy/no-card/permission/power"
                  + " verified",
              remote.level().dimension().location());
      remote.getInventory().clearContent();
      binder.getInventory().clearContent();
      feedPower = false;
    }

    private void curiosTerminal() {
      var previous = remote.getInventory().getItem(0);
      remote.getInventory().clearContent();
      remote.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
      var inventory = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(remote).orElseThrow(IllegalStateException::new);
      var original = new java.util.HashMap<>(inventory.getCurios());
      try {
        var first = new top.theillusivec4.curios.common.inventory.CurioStacksHandler(
            inventory, "a_test", 2, true, true, true,
            top.theillusivec4.curios.api.type.capability.ICurio.DropRule.DEFAULT);
        var last = new top.theillusivec4.curios.common.inventory.CurioStacksHandler(
            inventory, "z_test", 1, true, true, true,
            top.theillusivec4.curios.api.type.capability.ICurio.DropRule.DEFAULT);
        inventory.setCurios(java.util.Map.of("z_test", last, "a_test", first));
        var terminal = bind("refinedstorageaddons:wireless_crafting_grid");
        energy(terminal).receiveEnergy(Integer.MAX_VALUE, false);
        first.getCosmeticStacks().setStackInSlot(0, terminal);
        h.assertTrue(Destinations.get(remote, 1).unavailable(), "cosmetic terminal excluded");
        first.getStacks().setStackInSlot(1, terminal);
        last.getStacks().setStackInSlot(0, terminal.copy());
        remote.getInventory().setItem(0, stack("refinedstorage:wireless_grid"));
        var candidates = RsCompat.terminals(remote);
        h.assertTrue(candidates.get(38) == terminal, "functional slots sorted; original stack retained");
        int before = energy(terminal).getEnergyStored();
        int gold = count(Items.GOLD_INGOT);
        var target = Destinations.get(remote, 1);
        h.assertTrue(!target.unavailable(), "invalid inventory terminal skipped for Curios terminal");
        target.insert(new ItemStack(Items.GOLD_INGOT, 3), true);
        h.assertTrue(energy(terminal).getEnergyStored() == before, "Curios simulation free");
        target.insert(new ItemStack(Items.GOLD_INGOT, 3), false);
        h.assertTrue(count(Items.GOLD_INGOT) == gold + 3, "Curios real network insert");
        h.assertTrue(energy(terminal).getEnergyStored() == before
            - RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage(), "Curios original stack charged once");
        var saved = first.serializeNBT();
        first.deserializeNBT(saved);
        h.assertTrue(energy(first.getStacks().getStackInSlot(1)).getEnergyStored()
            == before - RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage(), "Curios serialized energy retained");
        first.getStacks().setStackInSlot(1, ItemStack.EMPTY);
        last.getStacks().setStackInSlot(0, ItemStack.EMPTY);
        h.assertTrue(Destinations.get(remote, 1).unavailable(), "removed terminals not cached across calls");
      } finally {
        inventory.setCurios(original);
        remote.getInventory().clearContent();
        remote.getInventory().setItem(0, previous);
      }
    }

    private void terminal(String id, Item item, int amount) {
      remote.getInventory().clearContent();
      var terminal = bind(id);
      remote.getInventory().setItem(0, terminal);
      var energy = energy(terminal);
      energy.receiveEnergy(Integer.MAX_VALUE, false);
      int beforeEnergy = energy.getEnergyStored();
      int beforeCount = count(item);
      var target = Destinations.get(remote, 1);
      h.assertTrue(!target.unavailable(), id + " should connect: " + target.reason());
      var sample = new ItemStack(item, amount);
      h.assertTrue(target.insert(sample.copy(), true).isEmpty(), id + " simulation accepts batch");
      h.assertTrue(
          count(item) == beforeCount && energy.getEnergyStored() == beforeEnergy,
          id + " simulation changes neither real storage nor terminal energy");
      h.assertTrue(target.insert(sample.copy(), false).isEmpty(), id + " actual batch accepted");
      h.assertTrue(
          count(item) == beforeCount + amount, id + " actual items present in real storage");
      h.assertTrue(
          beforeEnergy - energy.getEnergyStored()
              == RS.SERVER_CONFIG.getWirelessGrid().getInsertUsage(),
          id + " charges exactly native insert fee once");
      energy.extractEnergy(Integer.MAX_VALUE, false);
      h.assertTrue(energy.getEnergyStored() == 0, id + " test drained terminal battery");
      reject("电量", "depleted " + id);
      energy.receiveEnergy(Integer.MAX_VALUE, false);
    }

    private ItemStack bind(String id) {
      var terminal = stack(id);
      binder.setItemInHand(InteractionHand.MAIN_HAND, terminal);
      var hit =
          new BlockHitResult(Vec3.atCenterOf(controllerPos), Direction.UP, controllerPos, false);
      // 通过该终端自己的原生 useOn 完成绑定，不手写绑定 NBT，也不打开无线 GUI。
      var result = terminal.useOn(new UseOnContext(binder, InteractionHand.MAIN_HAND, hit));
      h.assertTrue(
          result.consumesAction() && NetworkItem.isValid(terminal),
          id + " native binding succeeded");
      h.assertTrue(
          NetworkItem.getDimension(terminal).equals(h.getLevel().dimension()),
          id + " binds real controller dimension");
      binder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      return terminal;
    }

    private void noCard() {
      transmitter.getUpgrades().setStackInSlot(0, ItemStack.EMPTY);
      reject("覆盖", "cross dimension without card");
      transmitter.getUpgrades().setStackInSlot(0, stack("rsinfinitybooster:dimension_card"));
    }

    private void deniedPermission() {
      var card = stack("refinedstorage:security_card");
      SecurityCardItem.setPermission(card, Permission.INSERT, false);
      security.getCardsItems().setStackInSlot(0, card);
      h.assertTrue(
          !network.getSecurityManager().hasPermission(Permission.INSERT, remote),
          "native security manager denies global card");
      reject("权限", "native INSERT permission denied");
      security.getCardsItems().setStackInSlot(0, ItemStack.EMPTY);
      h.assertTrue(
          network.getSecurityManager().hasPermission(Permission.INSERT, remote),
          "native security permission restored");
    }

    private void powerFailure() {
      feedPower = false;
      // 清空真实控制器的原生能量实现，不替换 network 或目的地判定。
      h.assertTrue(
          network.getEnergyStorage() instanceof BaseEnergyStorage,
          "native controller energy implementation");
      ((BaseEnergyStorage) network.getEnergyStorage()).setStored(0);
      h.assertTrue(!network.canRun(), "empty real controller battery disables network");
      reject("离线", "controller powered off");
      network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
      h.assertTrue(network.canRun(), "controller native power restored");
    }

    private void reject(String reason, String label) {
      int beforeDiamond = count(Items.DIAMOND), beforeEmerald = count(Items.EMERALD);
      var terminal = remote.getInventory().getItem(0);
      int beforeEnergy = energy(terminal).getEnergyStored();
      var target = Destinations.get(remote, 1);
      h.assertTrue(
          target.unavailable() && target.reason().contains(reason), label + ": " + target.reason());
      h.assertTrue(
          target.insert(new ItemStack(Items.DIAMOND, 3), false).getCount() == 3,
          label + " keeps full remainder");
      h.assertTrue(
          count(Items.DIAMOND) == beforeDiamond
              && count(Items.EMERALD) == beforeEmerald
              && energy(terminal).getEnergyStored() == beforeEnergy,
          label + " changes neither storage nor terminal energy");
    }

    private int count(Item item) {
      return storage.getStorage().getStacks().stream()
          .filter(s -> s.is(item))
          .mapToInt(ItemStack::getCount)
          .sum();
    }

    private IEnergyStorage energy(ItemStack stack) {
      return stack
          .getCapability(ForgeCapabilities.ENERGY)
          .orElseThrow(() -> new IllegalStateException("terminal energy capability"));
    }

    private ItemStack stack(String id) {
      var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
      h.assertTrue(item != null, "registered test item " + id);
      return new ItemStack(item);
    }
  }
}
