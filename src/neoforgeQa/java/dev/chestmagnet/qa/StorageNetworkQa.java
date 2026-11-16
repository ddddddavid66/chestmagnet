package dev.chestmagnet.qa;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemTargetBlockEntity;
import com.refinedmods.refinedstorage.common.networking.WirelessTransmitterBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.chestmagnet.ChestMagnet;
import dev.chestmagnet.Data;
import dev.chestmagnet.Destinations;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import com.refinedmods.refinedstorage.common.storage.externalstorage.ExternalStorageBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class StorageNetworkQa {
  private StorageNetworkQa() {}

  static void prepare(ServerLevel level, BlockPos origin) throws Exception {
    place(level, origin, "refinedstorage:creative_controller");
    place(level, origin.east(), "refinedstorage:grid");
    place(level, origin.west(), "refinedstorage:1k_storage_block");
    place(level, origin.above(), "refinedstorage:security_manager");
    authoriseInsert(level, origin.above(), true);
    var transmitter = (com.refinedmods.refinedstorage.common.networking.WirelessTransmitterBlock)
        block("refinedstorage:wireless_transmitter");
    level.setBlockAndUpdate(origin.north(), transmitter.rotated(Direction.SOUTH));
    var external = ((ExternalStorageBlock) block("refinedstorage:external_storage")).rotated(Direction.SOUTH);
    level.setBlockAndUpdate(origin.south(), external);
    var chest = NeoForgeProductionQa.container(level, origin.south(2), Blocks.BARREL);
    chest.setItem(0, new ItemStack(Items.COPPER_INGOT, 4));
  }

  static void verify(ServerLevel overworld, BlockPos origin) throws Exception {
    var api = RefinedStorageApi.INSTANCE;
    var grid = (NetworkItemTargetBlockEntity) overworld.getBlockEntity(origin.east());
    var storage = grid.getNetworkForItem().getComponent(StorageNetworkComponent.class);
    var binder = NeoForgeProductionQa.player(overworld, "StorageNetworkQA", origin);
    var localMagnet = new ItemStack(ChestMagnet.MAGNET.get());
    Data.edit(localMagnet, t -> t.putInt("Mode", 1));
    ItemStack standard = item("refinedstorage:wireless_grid");
    var nativeGrid = api.getNetworkItemHelper();
    binder.setItemInHand(InteractionHand.MAIN_HAND, standard);
    require(nativeGrid.bind(new UseOnContext(binder, InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(origin.east()), Direction.UP, origin.east(), false))).consumesAction()
        && nativeGrid.isBound(standard), "native RS binding");
    api.getEnergyStorage(standard).orElseThrow().receive(15000, Action.EXECUTE);
    binder.getInventory().setItem(3, localMagnet);
    var barrel = NeoForgeProductionQa.container(overworld, origin.above(2), Blocks.BARREL);
    barrel.setItem(0, new ItemStack(Items.GOLD_INGOT, 15));
    long before = api.getEnergyStorage(standard).orElseThrow().getStored();
    NeoForgeProductionQa.work(binder, localMagnet);
    require(barrel.isEmpty() && storage.get(new ItemResource(Items.GOLD_INGOT)) == 15
        && before > api.getEnergyStorage(standard).orElseThrow().getStored(), "same-dimension RS: remainder=" + barrel.getItem(0) + ", stored="
        + storage.get(new ItemResource(Items.GOLD_INGOT)) + ", energy=" + before + " -> " + api.getEnergyStorage(standard).orElseThrow().getStored()
        + ", available=" + Destinations.get(binder, 1).unavailable() + ", status=" + Destinations.get(binder, 1).reason());
    var external = (net.minecraft.world.Container) overworld.getBlockEntity(origin.south(2));
    require(external.getItem(0).getCount() == 4, "RS external storage is not recollected");

    var nether = overworld.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
    var far = new BlockPos(16, 76, 16);
    nether.getChunk(far);
    var remote = NeoForgeProductionQa.player(nether, "RemoteStorageQA", far);
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    Data.edit(magnet, t -> t.putInt("Mode", 1));
    remote.getInventory().setItem(1, magnet);
    remote.getInventory().setItem(0, standard);
    var remoteBarrel = NeoForgeProductionQa.container(nether, far.above(), Blocks.BARREL);
    remoteBarrel.setItem(0, new ItemStack(Items.IRON_INGOT, 13));
    before = api.getEnergyStorage(standard).orElseThrow().getStored();
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.getItem(0).getCount() == 13 && api.getEnergyStorage(standard).orElseThrow().getStored() == before,
        "cross-dimension without a card is rejected");
    var transmitter = (WirelessTransmitterBlockEntity) overworld.getBlockEntity(origin.north());
    require(transmitter.addUpgrade(item("rsinfinitybooster:dimension_card")), "native dimension card installed");
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.isEmpty() && storage.get(new ItemResource(Items.IRON_INGOT)) == 13
        && api.getEnergyStorage(standard).orElseThrow().getStored() < before, "cross-dimension RS received 13 and charged");
    var crafting = item("refinedstorage_quartz_arsenal:wireless_crafting_grid");
    binder.setItemInHand(InteractionHand.MAIN_HAND, crafting);
    nativeGrid.bind(new UseOnContext(binder, InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(origin.east()), Direction.UP, origin.east(), false)));
    var craftEnergy = api.getEnergyStorage(crafting).orElseThrow();
    craftEnergy.receive(15000, Action.EXECUTE);
    before = api.getEnergyStorage(crafting).orElseThrow().getStored();
    remote.setItemInHand(InteractionHand.MAIN_HAND, crafting);
    remoteBarrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.isEmpty() && storage.get(new ItemResource(Items.DIAMOND)) == 7
        && before > api.getEnergyStorage(crafting).orElseThrow().getStored(), "Quartz crafting terminal stores across dimensions");
    var curios = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(remote).orElseThrow();
    require(curios.getStacksHandler("refinedstorage_curios_integration").isPresent(), "native RS accessory slot exists");
    remote.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    curios.setEquippedCurio("refinedstorage_curios_integration", 0, crafting);
    var worn = curios.getStacksHandler("refinedstorage_curios_integration").orElseThrow().getStacks().getStackInSlot(0);
    long wornBefore = api.getEnergyStorage(worn).orElseThrow().getStored();
    remoteBarrel.setItem(0, new ItemStack(Items.REDSTONE, 9));
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.isEmpty() && storage.get(new ItemResource(Items.REDSTONE)) == 9
        && api.getEnergyStorage(worn).orElseThrow().getStored() < wornBefore, "wireless crafting grid in Curios accepted");
    api.getEnergyStorage(worn).orElseThrow().extract(Long.MAX_VALUE, Action.EXECUTE);
    remoteBarrel.setItem(0, new ItemStack(Items.LAPIS_LAZULI, 6));
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.getItem(0).getCount() == 6 && storage.get(new ItemResource(Items.LAPIS_LAZULI)) == 0,
        "depleted wireless terminal leaves source untouched");
    authoriseInsert(overworld, origin.above(), false);
    api.getEnergyStorage(worn).orElseThrow().receive(1000, Action.EXECUTE);
    var afterCharge = api.getEnergyStorage(worn).orElseThrow().getStored();
    require(Destinations.get(remote, 1).unavailable(), "native RS security denies insertion");
    NeoForgeProductionQa.work(remote, magnet);
    require(remoteBarrel.getItem(0).getCount() == 6 && api.getEnergyStorage(worn).orElseThrow().getStored() == afterCharge,
        "denied insertion leaves source and energy unchanged");
    NeoForgeProductionQa.LOG.info("QA_NEOFORGE_RS ordinary=15 cross=13 crafting=7 Curios=9; loop/cardless/depleted/denied untouched");

    Data.edit(magnet, t -> t.putInt("Mode", 3));
    require(Destinations.get(remote, 3).unavailable(), "Beyond mode has no implicit network");
    var network = DimensionsNet.createNewNetForPlayer(remote, 100, 12);
    require(network != null && DimensionsNet.getPrimaryNetFromPlayer(remote) == network, "native primary network");
    remoteBarrel.setItem(0, new ItemStack(Items.EMERALD, 6));
    NeoForgeProductionQa.work(remote, magnet);
    var key = new ItemStackKey(new ItemStack(Items.EMERALD));
    long quantity = network.getUnifiedStorage().getFuzzyMatches(key).stream()
        .filter(entry -> entry.key().equals(key)).mapToLong(entry -> entry.amount()).sum();
    require(quantity == 6 && remoteBarrel.isEmpty(), "Beyond actual primary received 6");
    var other = NeoForgeProductionQa.player(nether, "OtherNetQA", far);
    var second = DimensionsNet.createNewNetForPlayer(other, 100, 12);
    require(second != null && second != network && !DimensionsNet.setPrimaryNetForPlayer(remote, second),
        "non-member cannot bind another primary network");
    second.addPlayer(remote.getUUID());
    DimensionsNet.setPrimaryNetForPlayer(remote, second);
    var selected = DimensionsNet.getPrimaryNetFromPlayer(remote);
    require(selected == second, "member can switch primary: selected="
        + (selected == null ? "null" : selected.getId()) + " second=" + second.getId()
        + " member=" + second.getPlayers().contains(remote.getUUID())
        + " known=" + DimensionsNet.getAllNetFromPlayer(remote).stream().map(DimensionsNet::getId).toList());
    remoteBarrel.setItem(0, new ItemStack(Items.EMERALD, 5));
    NeoForgeProductionQa.work(remote, magnet);
    require(second.getUnifiedStorage().getStackByKey(key).amount() == 5
        && network.getUnifiedStorage().getStackByKey(key).amount() == 6
        && remoteBarrel.isEmpty(), "same magnet follows primary switch without duplicate acceptance");
    NeoForgeProductionQa.LOG.info("QA_NEOFORGE_BEYOND remote dimension first primary=6 second primary=5 leftover=0");
  }

  private static void authoriseInsert(ServerLevel level, BlockPos at, boolean insert) throws Exception {
    var sec = (com.refinedmods.refinedstorage.common.security.SecurityManagerBlockEntity)level.getBlockEntity(at);
    var card = item("refinedstorage:fallback_security_card");
    var permissions = new com.refinedmods.refinedstorage.common.security.SecurityCardPermissions(java.util.Map.of(
        com.refinedmods.refinedstorage.common.security.BuiltinPermission.OPEN, true,
        com.refinedmods.refinedstorage.common.security.BuiltinPermission.INSERT, insert,
        com.refinedmods.refinedstorage.common.security.BuiltinPermission.EXTRACT, true));
    card.set(com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE.getSecurityCardPermissions(), permissions);
    var fallback = sec.getClass().getDeclaredMethod("getFallbackSecurityCard");
    fallback.setAccessible(true);
    ((net.minecraft.world.Container)fallback.invoke(sec)).setItem(0, card);
  }

  private static void place(ServerLevel level, BlockPos at, String id) {
    level.setBlockAndUpdate(at, block(id).defaultBlockState());
  }

  private static net.minecraft.world.level.block.Block block(String name) {
    var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(name));
    require(block != Blocks.AIR, "registered block " + name);
    return block;
  }

  private static ItemStack item(String name) {
    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(name));
    require(item != Items.AIR, "registered item " + name);
    return new ItemStack(item);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
