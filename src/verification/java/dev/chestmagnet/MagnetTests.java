package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class MagnetTests {
  private static net.minecraft.server.level.ServerPlayer player(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "MagnetTest"));
    p.getInventory().clearContent();
    p.setPos(
        h.absolutePos(new BlockPos(2, 1, 2)).getX(),
        h.absolutePos(new BlockPos(2, 1, 2)).getY(),
        h.absolutePos(new BlockPos(2, 1, 2)).getZ());
    return p;
  }

  @GameTest(template = "empty")
  public static void inventory(GameTestHelper h) {
    var p = player(h);
    var dest = Destinations.get(p, 0);
    var sample = new ItemStack(Items.DIAMOND, 64);
    h.assertTrue(dest.insert(sample, true).isEmpty(), "simulation capacity");
    h.assertTrue(p.getInventory().isEmpty(), "simulation must not mutate");
    dest.insert(sample, false);
    h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 64, "actual insert");
    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
    h.assertTrue(dest.insert(sample, false).getCount() == 64, "full target keeps remainder");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void chestAndExclusion(GameTestHelper h) {
    var p = player(h);
    h.setBlock(2, 1, 2, Blocks.CHEST);
    var be = h.getBlockEntity(new BlockPos(2, 1, 2));
    ((ChestBlockEntity) be).setItem(0, new ItemStack(Items.EMERALD, 17));
    h.assertTrue(Sources.eligible(be), "chest eligible");
    var inv = Sources.inventory(p, be);
    h.assertTrue(inv.extractItem(0, 64, true).getCount() == 17, "source simulation");
    h.assertTrue(inv.getStackInSlot(0).getCount() == 17, "source unchanged");
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    var ref = Sources.of(be);
    Data.toggle(m, ref.key(p), ref.label(p));
    h.assertTrue(Data.excluded(m, ref.key(p)), "exclusion saved");
    var loaded = ItemStack.of(m.save(new net.minecraft.nbt.CompoundTag()));
    h.assertTrue(Data.excluded(loaded, ref.key(p)), "NBT persistence");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void carts(GameTestHelper h) {
    var p = player(h);
    var at = p.position();
    var c = new MinecartChest(h.getLevel(), at.x, at.y, at.z);
    h.getLevel().addFreshEntity(c);
    c.setItem(0, new ItemStack(Items.GOLD_INGOT, 33));
    h.assertTrue(Sources.eligible(c), "chest cart eligible");
    h.assertTrue(
        Sources.eligible(new MinecartHopper(h.getLevel(), at.x, at.y, at.z)),
        "hopper cart eligible");
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    var ref = Sources.of(c);
    Data.toggle(m, ref.key(p), ref.label(p));
    c.setPos(at.x + 100, at.y, at.z);
    h.assertTrue(!Sources.inside(p, m, c), "moving cart out of range");
    h.assertTrue(Data.excluded(m, ref.key(p)), "cart exclusion follows UUID");
    c.discard();
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void lootrPersonal(GameTestHelper h) {
    if (!net.minecraftforge.fml.ModList.get().isLoaded("lootr")) {
      h.succeed();
      return;
    }
    var p = player(h);
    var q = player(h);
    var block =
        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
            new net.minecraft.resources.ResourceLocation("lootr", "lootr_chest"));
    h.assertTrue(block != null, "Lootr registered");
    h.setBlock(2, 1, 2, block);
    var be =
        (net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity)
            h.getBlockEntity(new BlockPos(2, 1, 2));
    be.setLootTable(
        new net.minecraft.resources.ResourceLocation("minecraft", "chests/simple_dungeon"), 42L);
    var a = Sources.inventory(p, be);
    var b = Sources.inventory(q, be);
    h.assertTrue(a != null && b != null, "no-menu personal inventories");
    h.assertTrue(
        p.containerMenu == p.inventoryMenu && q.containerMenu == q.inventoryMenu,
        "must not open menu");
    a.insertItem(0, new ItemStack(Items.NETHER_STAR), false);
    h.assertTrue(a != b, "separate personal wrappers");
    h.assertTrue(h.getLevel().getBlockEntity(be.getBlockPos()) == be, "source not destroyed");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void backpackRules(GameTestHelper h) {
    if (!net.minecraftforge.fml.ModList.get().isLoaded("sophisticatedbackpacks")) {
      h.succeed();
      return;
    }
    var p = player(h);
    var item =
        net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
            new net.minecraft.resources.ResourceLocation("sophisticatedbackpacks", "backpack"));
    var bag = new ItemStack(item);
    p.getInventory().setItem(0, bag);
    var wrapper =
        bag.getCapability(
                net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
                    .getCapabilityInstance())
            .orElseThrow(() -> new IllegalStateException("wrapper"));
    var dest = Destinations.get(p, 2);
    h.assertTrue(
        dest.insert(new ItemStack(Items.DIAMOND, 12), true).isEmpty(), "backpack simulation");
    h.assertTrue(wrapper.getInventoryHandler().getStackInSlot(0).isEmpty(), "simulation unchanged");
    dest.insert(new ItemStack(Items.DIAMOND, 12), false);
    h.assertTrue(
        wrapper.getInventoryHandler().getStackInSlot(0).getCount() == 12, "backpack actual stored");
    var upgrade =
        net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
            new net.minecraft.resources.ResourceLocation("sophisticatedbackpacks", "void_upgrade"));
    wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(upgrade));
    var voids =
        wrapper
            .getUpgradeHandler()
            .getWrappersThatImplement(
                net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper.class);
    h.assertTrue(!voids.isEmpty(), "void upgrade installed");
    var v = voids.get(0);
      configureVoid(v);
    v.getFilterLogic().setEmptyAllowListMatchesEverything();
    h.assertTrue(
        Destinations.get(p, 2).insert(new ItemStack(Items.EMERALD, 9), false).isEmpty(),
        "void accepted not skipped");
    boolean emerald = false;
    for (int i = 0; i < wrapper.getInventoryHandler().getSlots(); i++)
      emerald |= wrapper.getInventoryHandler().getStackInSlot(i).is(Items.EMERALD);
    h.assertTrue(!emerald, "accepted items destroyed by upgrade");
    h.succeed();
  }

  private static void configureVoid(Object upgrade) {
    try {
      try {
        var type = Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType");
        upgrade.getClass().getMethod("setVoidType", type).invoke(upgrade, type.getField("ALWAYS").get(null));
      } catch (ClassNotFoundException e) {
        upgrade.getClass().getMethod("setShouldVoidOverflow", boolean.class).invoke(upgrade, false);
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not configure native void upgrade", e);
    }
  }

  private static net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper bagWrapper(ItemStack stack) {
    return stack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper
        .getCapabilityInstance()).orElseThrow(IllegalStateException::new);
  }

  @GameTest(template = "empty")
  public static void backpackRemainder(GameTestHelper h) {
    var p = player(h);
    var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
        new net.minecraft.resources.ResourceLocation("sophisticatedbackpacks", "backpack"));
    var a = new ItemStack(item);
    var b = new ItemStack(item);
    var c = new ItemStack(item);
    p.getInventory().setItem(4, a);
    p.getInventory().setItem(19, b);
    p.getInventory().setItem(35, c);
    var first = bagWrapper(a).getInventoryHandler();
    var second = bagWrapper(b).getInventoryHandler();
    var third = bagWrapper(c).getInventoryHandler();
    for (int i = 0; i < first.getSlots(); i++) first.setStackInSlot(i, new ItemStack(Items.STONE, 64));
    first.setStackInSlot(0, new ItemStack(Items.DIAMOND, 61));
    var upgrade = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
        new net.minecraft.resources.ResourceLocation("sophisticatedbackpacks", "filter_upgrade"));
    var wrappedSecond = bagWrapper(b);
    wrappedSecond.getUpgradeHandler().setStackInSlot(0, new ItemStack(upgrade));
    var filter = wrappedSecond.getUpgradeHandler().getWrappersThatImplement(
        net.p3pp3rf1y.sophisticatedcore.upgrades.filter.FilterUpgradeWrapper.class).get(0);
    filter.setDirection(net.p3pp3rf1y.sophisticatedcore.upgrades.filter.Direction.INPUT);
    filter.getFilterLogic().setAllowList(true);
    filter.getFilterLogic().getFilterHandler().setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
    var target = Destinations.get(p, 2);
    var diamonds = new ItemStack(Items.DIAMOND, 8);
    h.assertTrue(target.insert(diamonds, true).isEmpty() && first.getStackInSlot(0).getCount() == 61
        && third.getStackInSlot(0).isEmpty(), "simulation across multiple bags");
    h.assertTrue(target.insert(diamonds, false).isEmpty(), "slot 35 receives remainder");
    h.assertTrue(first.getStackInSlot(0).getCount() == 64 && second.getStackInSlot(0).isEmpty()
        && third.getStackInSlot(0).getCount() == 5, "partial first, filtered second, third got 5");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void unavailableRs(GameTestHelper h) {
    var p = player(h);
    var d = Destinations.get(p, 1);
    h.assertTrue(
        d.insert(new ItemStack(Items.DIAMOND, 5), false).getCount() == 5,
        "missing grid must refuse without fallback");
    h.assertTrue(!d.reason().isBlank(), "reason present");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void engineTransfer(GameTestHelper h) {
    var p = player(h);
    h.setBlock(2, 1, 2, Blocks.BARREL);
    var be =
        (net.minecraft.world.level.block.entity.BarrelBlockEntity)
            h.getBlockEntity(new BlockPos(2, 1, 2));
    be.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    var state = new Engine.State();
    state.magnet = Data.id(m);
    state.dimension = p.level().dimension().location().toString();
    state.range = "8:3:8";
    var ref = Sources.of(be);
    state.sources.put(ref.key(p), ref);
    long start = System.nanoTime();
    new Engine().work(p, m, state, Long.MAX_VALUE);
    h.assertTrue(p.getInventory().countItem(Items.IRON_INGOT) == 64, "scheduler transfer");
    h.assertTrue(be.getItem(0).isEmpty(), "source extracted");
    h.assertTrue(!Data.tag(m).contains("Recovery"), "no pending remainder");
    org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
        .info("Single-source engine smoke: {} us", (System.nanoTime() - start) / 1000);
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void lootrCart(GameTestHelper h) {
    if (!net.minecraftforge.fml.ModList.get().isLoaded("lootr")) {
      h.succeed();
      return;
    }
    var p = player(h);
    var at = p.position();
    var c =
        new noobanidus.mods.lootr.entity.LootrChestMinecartEntity(h.getLevel(), at.x, at.y, at.z);
    c.setLootTable(
        new net.minecraft.resources.ResourceLocation("minecraft", "chests/abandoned_mineshaft"),
        123L);
    h.getLevel().addFreshEntity(c);
    h.assertTrue(Sources.eligible(c), "Lootr cart eligible");
    h.assertTrue(Sources.inventory(p, c) != null, "Lootr cart personal inventory");
    h.assertTrue(c.isAlive() && p.containerMenu == p.inventoryMenu, "no destruction/menu");
    c.discard();
    h.succeed();
  }
}
