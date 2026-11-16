package dev.chestmagnet.qa;

import com.mojang.authlib.GameProfile;
import dev.chestmagnet.ChestMagnet;
import dev.chestmagnet.Data;
import dev.chestmagnet.Engine;
import dev.chestmagnet.Sources;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;

@Mod("chestmagnetqa")
@EventBusSubscriber(modid = "chestmagnetqa")
public final class NeoForgeProductionQa {
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-neoforge-qa");
  private static int ticks;
  private static final String CASE = System.getProperty("chestmagnet.qa", "");
  private static boolean done;

  @SubscribeEvent
  public static void run(ServerTickEvent.Post event) {
    if (done || !java.util.Set.of("minimal", "lowest", "current", "locked", "quests").contains(CASE)) return;
    var level = event.getServer().overworld();
    var pos = level.getSharedSpawnPos().above(2);
    try {
      ticks++;
      if (CASE.equals("lowest") && ticks == 8) StorageNetworkQa.prepare(level, pos.above(10));
      if (ticks < (CASE.equals("lowest") ? 28 : 8)) return;
      done = true;
      var player = player(level, "ChestMagnetQA", pos);
      var magnet = new ItemStack(ChestMagnet.MAGNET.get());
      player.getInventory().setItem(0, magnet);
      var recipe = level.getRecipeManager().byKey(id("chestmagnet:chest_magnet")).orElseThrow();
      require(recipe.value().getResultItem(level.registryAccess()).is(ChestMagnet.MAGNET.get()), "recipe output");
      normal(level, pos, player, magnet);
      migration(level, player, magnet);
      if (ModList.get().isLoaded("sophisticatedbackpacks")) backpacks(level, pos, player, magnet);
      if (CASE.equals("lowest")) StorageNetworkQa.verify(level, pos.above(10));
      if (ModList.get().isLoaded("lootr")) lootr(level, pos, realPlayer(level, "ChestMagnetLootA", pos));
      if (ModList.get().isLoaded("quark")) quark(level, pos, player);
      if (CASE.equals("locked")) locked(level, pos, player, magnet);
      if (CASE.equals("quests")) QuestProductionQa.verify(level, realPlayer(level, "NativeFTBQA", pos));
      LOG.info("QA_NEOFORGE_PASS {}", CASE);
    } catch (Throwable ex) {
      done = true;
      LOG.error("QA_NEOFORGE_FAILURE {}", CASE, ex);
    }
  }

  private static void normal(ServerLevel level, BlockPos pos, ServerPlayer p, ItemStack magnet) throws Exception {
    var barrel = container(level, pos.east(), Blocks.BARREL);
    barrel.setItem(0, new ItemStack(Items.POTATO, 11));
    var frame = new net.minecraft.world.entity.decoration.ItemFrame(level, pos.north(), Direction.SOUTH);
    frame.setItem(new ItemStack(Items.PAPER));
    var frameHandler = Sources.inventory(p, frame, magnet);
    require(frameHandler.extractItem(0, 1, true).is(Items.PAPER) && !frame.getItem().isEmpty(), "frame simulation");
    var paper = frameHandler.extractItem(0, 1, false);
    require(paper.is(Items.PAPER) && frame.getItem().isEmpty(), "frame extracted");
    require(frameHandler.insertItem(0, paper, false).isEmpty() && frame.getItem().is(Items.PAPER), "frame rollback");
    work(p, magnet);
    require(barrel.isEmpty() && p.getInventory().countItem(Items.POTATO) == 11, "nearby transfer exactly once");
    work(p, magnet);
    require(p.getInventory().countItem(Items.POTATO) == 11, "empty source does not pay twice");
    for (int slot = 1; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
    p.getInventory().setItem(1, new ItemStack(Items.POTATO, 62));
    barrel.setItem(0, new ItemStack(Items.POTATO, 11));
    work(p, magnet);
    require(p.getInventory().countItem(Items.POTATO) == 64 && barrel.getItem(0).getCount() == 9, "partial, real residual");
    barrel.clearContent();
    for (int slot = 1; slot < 36; slot++) p.getInventory().setItem(slot, ItemStack.EMPTY);
    LOG.info("QA_NEOFORGE_BASE nearby, partial, frame rollback, recipe");
  }

  private static void migration(ServerLevel level, ServerPlayer p, ItemStack magnet) throws Exception {
    // 1.20.1 inner cache retains old ItemStack NBT when vanilla migrates the item's outer tag.
    var legacy = new CompoundTag();
    legacy.putString("id", "minecraft:stick");
    legacy.putByte("Count", (byte) 17);
    Data.edit(magnet, t -> { t.putInt("X", 5); t.putBoolean("AutoStructure", true); t.put("Recovery", legacy.copy()); });
    var oldOuter = new CompoundTag();
    oldOuter.putString("id", "chestmagnet:chest_magnet");
    oldOuter.putByte("Count", (byte)1);
    var oldTag = new CompoundTag();
    oldTag.put("ChestMagnet", Data.tag(magnet).copy());
    oldOuter.put("tag", oldTag);
    var decoder = Class.forName("dev.chestmagnet.StackData").getDeclaredMethod(
        "read", net.minecraft.core.HolderLookup.Provider.class, CompoundTag.class);
    decoder.setAccessible(true);
    var restored = (ItemStack)decoder.invoke(null, level.registryAccess(), oldOuter);
    require(restored.is(ChestMagnet.MAGNET.get()), "whole 1.20.1 magnet serialized through vanilla migration");
    restored = ItemStack.parse(level.registryAccess(), restored.save(level.registryAccess())).orElseThrow();
    var stick = (ItemStack) decoder.invoke(null, level.registryAccess(), Data.tag(restored).getCompound("Recovery"));
    require(Data.radius(restored, "X") == 5 && Data.autoStructure(restored)
        && stick.is(Items.STICK) && stick.getCount() == 17, "legacy fields and cached item migration");
    var removed = legacy.copy();
    removed.putString("id", "tetra:forged_workbench");
    var pending = new ListTag();
    pending.add(removed);
    Data.edit(magnet, t -> { t.remove("Recovery"); t.put("PendingLoot", pending); });
    work(p, magnet);
    var isolated = Data.tag(magnet).getList("Quarantined", 10).getCompound(0);
    require(!Data.tag(magnet).contains("PendingLoot") && isolated.getList("Payload", 10).getCompound(0).equals(removed),
        "unregistered cached item kept unchanged, not delivered");
    Data.edit(magnet, t -> { t.remove("Quarantined"); t.remove("X"); t.remove("AutoStructure"); });
    LOG.info("QA_NEOFORGE_MIGRATION settings, old item stack, absent mod cache");
  }

  private static void backpacks(ServerLevel level, BlockPos pos, ServerPlayer p, ItemStack magnet) throws Exception {
    Item bag = BuiltInRegistries.ITEM.get(id("sophisticatedbackpacks:backpack"));
    require(bag != Items.AIR, "backpack registered");
    var first = new ItemStack(bag);
    var second = new ItemStack(bag);
    p.getInventory().setItem(2, first);
    p.getInventory().setItem(3, second);
    var front = BackpackWrapper.fromStack(first).getInventoryHandler();
    var rear = BackpackWrapper.fromStack(second).getInventoryHandler();
    for (int i = 0; i < front.getSlots(); i++) front.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
    front.setStackInSlot(0, new ItemStack(Items.CARROT, 62));
    var barrel = container(level, pos.east(), Blocks.BARREL);
    barrel.setItem(0, new ItemStack(Items.CARROT, 11));
    Data.edit(magnet, t -> t.putInt("Mode", 2));
    work(p, magnet);
    require(barrel.isEmpty() && front.getStackInSlot(0).getCount() == 64
        && rear.getStackInSlot(0).getCount() == 9 && p.getInventory().countItem(Items.CARROT) == 0,
        "spill from first backpack to second backpack");
    front.setStackInSlot(1, ItemStack.EMPTY);
    var wrapper = BackpackWrapper.fromStack(first);
    var type = net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper.class;
    wrapper.getUpgradeHandler().setStackInSlot(0,
        new ItemStack(BuiltInRegistries.ITEM.get(id("sophisticatedbackpacks:advanced_void_upgrade"))));
    var voider = wrapper.getUpgradeHandler().getWrappersThatImplement(type).getFirst();
    voider.setVoidType(net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType.ALWAYS);
    voider.getFilterLogic().setAllowList(true);
    voider.getFilterLogic().getFilterHandler().setStackInSlot(0, new ItemStack(Items.ECHO_SHARD));
    ((BackpackWrapper)wrapper).refreshInventoryForUpgradeProcessing();
    ((BackpackWrapper)wrapper).refreshInventoryForInputOutput();
    var sacrifice = new ItemStack(Items.ECHO_SHARD, 4);
    require(voider.stackMatchesFilter(sacrifice)
        && wrapper.getInventoryForInputOutput().insertItem(sacrifice, true).isEmpty(),
        "native backpack void fixture: mode=" + voider.getVoidType() + " filter=" + voider.stackMatchesFilter(sacrifice)
        + " native remains=" + wrapper.getInventoryForInputOutput().insertItem(sacrifice, true));
    barrel.setItem(0, sacrifice);
    work(p, magnet);
    boolean stored = false;
    for (int slot = 0; slot < rear.getSlots(); slot++) stored |= rear.getStackInSlot(slot).is(Items.ECHO_SHARD);
    for (int slot = 0; slot < front.getSlots(); slot++) stored |= front.getStackInSlot(slot).is(Items.ECHO_SHARD);
    require(barrel.isEmpty() && p.getInventory().countItem(Items.ECHO_SHARD) == 0 && !stored,
        "voided source stack is consumed and never sent to second backpack: source=" + barrel.getItem(0)
        + " first=" + front.getStackInSlot(0) + " second=" + rear.getStackInSlot(1));
    Data.edit(magnet, t -> t.putInt("Mode", 0));
    LOG.info("QA_NEOFORGE_BACKPACK first backpack=2 second=9 leftover=0; 4 matching voided");

  }

  private static void lootr(ServerLevel level, BlockPos pos, ServerPlayer one) throws Exception {
    var two = realPlayer(level, "ChestMagnetLootB", pos);
    for (String key : java.util.List.of("lootr:lootr_chest", "quark:lootr_spruce_chest", "quark:trapped_lootr_jungle_chest")) {
      var block = BuiltInRegistries.BLOCK.get(id(key));
      require(block != Blocks.AIR, key + " registered");
      var at = pos.north(2);
      level.removeBlock(at, false);
      var chest = (RandomizableContainerBlockEntity) container(level, at, block);
      loot(chest);
      testPersonal(chest, key, one, two);
      require(level.getBlockEntity(at) == chest, key + " untouched block");
    }
    var cartType = BuiltInRegistries.ENTITY_TYPE.get(id("lootr:lootr_minecart"));
    var entity = cartType.create(level);
    require(entity instanceof AbstractMinecartContainer, "Lootr chest minecart registered");
    var cart = (AbstractMinecartContainer) entity;
    cart.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
    cart.setLootTableSeed(1001L);
    testPersonal(cart, "Lootr minecart", one, two);
    require(!cart.isRemoved(), "Lootr minecart not removed");
    LOG.info("QA_NEOFORGE_LOOTR two-player personal chest, trapped chest, cart, no GUI");
  }

  private static void testPersonal(Object source, String name, ServerPlayer one, ServerPlayer two) {
    require(Sources.eligible(source), name + " eligible");
    var a = Sources.inventory(one, source);
    var b = Sources.inventory(two, source);
    require(a != null && b != null, name + " personal inventory available");
    int totalA = 0, totalB = 0;
    for (int i = 0; i < b.getSlots(); i++) totalB += b.getStackInSlot(i).getCount();
    for (int i = 0; i < a.getSlots(); i++) totalA += a.extractItem(i, 64, false).getCount();
    int afterA = 0, afterB = 0;
    var live = Sources.inventory(one, source);
    for (int i = 0; i < live.getSlots(); i++) afterA += live.getStackInSlot(i).getCount();
    for (int i = 0; i < b.getSlots(); i++) afterB += b.getStackInSlot(i).getCount();
    require(totalA > 0 && totalB > 0 && afterA == 0 && afterB == totalB
        && one.containerMenu == one.inventoryMenu && two.containerMenu == two.inventoryMenu,
        name + " isolated counts a=" + totalA + " b=" + totalB + " afterA=" + afterA + " afterB=" + afterB);
    LOG.info("QA_NEOFORGE_PERSONAL {} extracted={} second_unchanged={}", name, totalA, totalB);
  }

  private static void loot(RandomizableContainerBlockEntity chest) {
    chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 1001L);
  }

  private static void quark(ServerLevel level, BlockPos pos, ServerPlayer p) {
    var block = (ChestBlock) BuiltInRegistries.BLOCK.get(id("quark:oak_chest"));
    var first = pos.south(2);
    var second = first.east();
    level.setBlock(first, block.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        .setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    level.setBlock(second, block.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        .setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    var a = (BaseContainerBlockEntity)level.getBlockEntity(first);
    var b = (BaseContainerBlockEntity)level.getBlockEntity(second);
    a.setItem(0, new ItemStack(Items.CHERRY_LOG, 7));
    b.setItem(0, new ItemStack(Items.CHERRY_LOG, 5));
    var pair = Sources.inventory(p, a);
    require(pair != null && pair.getSlots() == 54, "Quark native double chest");
    int collected = 0;
    for (int i = 0; i < pair.getSlots(); i++) collected += pair.extractItem(i, 64, false).getCount();
    require(collected == 12 && a.isEmpty() && b.isEmpty(), "double chest has 12, no remaining");
    var crateAt = pos.south(3);
    var crateBlock = BuiltInRegistries.BLOCK.get(id("quark:crate"));
    require(crateBlock != Blocks.AIR, "crate registered");
    level.setBlockAndUpdate(crateAt, crateBlock.defaultBlockState());
    var raw = level.getCapability(Capabilities.ItemHandler.BLOCK, crateAt, (Direction) null);
    var handler = Sources.inventory(p, level.getBlockEntity(crateAt));
    require(raw != null && handler != null, "crate exposes NeoForge capability");
    require(raw.insertItem(0, new ItemStack(Items.COPPER_INGOT, 11), false).isEmpty(), "crate fixture");
    require(handler.extractItem(0, 9, false).getCount() == 9 && raw.getStackInSlot(0).getCount() == 2, "crate residual");
    var boat = BuiltInRegistries.ENTITY_TYPE.get(id("quark:quark_chest_boat")).create(level);
    var inv = Sources.inventory(p, boat);
    require(Sources.eligible(boat) && inv != null && inv.insertItem(0, new ItemStack(Items.CARROT, 7), false).isEmpty()
        && inv.extractItem(0, 7, false).getCount() == 7, "Quark storage boat real extraction");
    LOG.info("QA_NEOFORGE_QUARK double chest, crate, storage boat");
  }

  private static void locked(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack magnet) throws Exception {
    var aetherAt = pos.east(2);
    var treasure = container(level, aetherAt, BuiltInRegistries.BLOCK.get(id("aether:treasure_chest")));
    var getLocked = treasure.getClass().getMethod("getLocked");
    var getKind = treasure.getClass().getMethod("getKind");
    var kind = id("aether:silver");
    treasure.getClass().getMethod("setKind", ResourceLocation.class).invoke(treasure, kind);
    treasure.getClass().getMethod("setLocked", boolean.class).invoke(treasure, true);
    treasure.setItem(0, new ItemStack(Items.DIAMOND, 5));
    work(player, magnet);
    require(treasure.isEmpty() && level.getBlockEntity(aetherAt) == treasure
        && !(boolean) getLocked.invoke(treasure) && kind.equals(getKind.invoke(treasure))
        && player.getInventory().countItem(Items.DIAMOND) == 5, "native Aether unlock retains kind and actual cargo");

    var frozenAt = pos.west(2);
    var frozen = BuiltInRegistries.BLOCK.get(id("aquamirae:frozen_chest"));
    require(frozen != Blocks.AIR, "Aquamirae frozen chest registered");
    level.setBlockAndUpdate(frozenAt, frozen.defaultBlockState());
    work(player, magnet, 6);
    var chest = (RandomizableContainerBlockEntity)level.getBlockEntity(frozenAt);
    long count = player.getInventory().items.stream().filter(stack -> !stack.is(ChestMagnet.MAGNET.get())).mapToLong(ItemStack::getCount).sum();
    require(level.getBlockState(frozenAt).is(Blocks.CHEST) && chest != null && chest.isEmpty()
        && chest.getLootTable() == null && count > 5, "Aquamirae native table residue, block="
        + level.getBlockState(frozenAt) + ", count=" + count + ", empty=" + (chest != null && chest.isEmpty()));
    work(player, magnet);
    require(player.getInventory().items.stream().filter(stack -> !stack.is(ChestMagnet.MAGNET.get()))
        .mapToLong(ItemStack::getCount).sum() == count, "Aquamirae loot not regenerated");
    LOG.info("QA_NEOFORGE_LOCKED Aether permanent unlock and frozen chest real table");
  }

  static BaseContainerBlockEntity container(ServerLevel level, BlockPos pos, Block block) {
    level.setBlockAndUpdate(pos, block.defaultBlockState());
    return (BaseContainerBlockEntity) level.getBlockEntity(pos);
  }

  static ServerPlayer player(ServerLevel level, String name, BlockPos pos) {
    var p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name));
    p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
    return p;
  }

  private static ServerPlayer realPlayer(ServerLevel level, String name, BlockPos pos) {
    var profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
    var p = new ServerPlayer(level.getServer(), level, profile, net.minecraft.server.level.ClientInformation.createDefault());
    p.connection = new net.neoforged.neoforge.common.util.FakePlayer(level, profile).connection;
    p.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
    return p;
  }

  static void work(ServerPlayer p, ItemStack magnet) throws Exception { work(p, magnet, 1); }

  static void work(ServerPlayer p, ItemStack magnet, int passes) throws Exception {
    var state = Class.forName("dev.chestmagnet.Engine$State");
    var ctor = state.getDeclaredConstructor();
    ctor.setAccessible(true);
    Method work = Engine.class.getDeclaredMethod("work", ServerPlayer.class, ItemStack.class, state, long.class);
    work.setAccessible(true);
    var engine = new Engine();
    var cursor = ctor.newInstance();
    for (int i = 0; i < passes; i++) work.invoke(engine, p, magnet, cursor, System.nanoTime() + 250_000_000L);
  }

  private static ResourceLocation id(String name) { return ResourceLocation.parse(name.toLowerCase(Locale.ROOT)); }

  private static void require(boolean condition, String what) {
    if (!condition) throw new AssertionError(what);
  }
}
