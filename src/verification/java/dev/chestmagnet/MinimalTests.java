package dev.chestmagnet;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class MinimalTests {
  @GameTest(template = "empty")
  public static void deferredDestination(GameTestHelper h) {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var target = Destinations.deferred(() -> {
      calls.incrementAndGet();
      return Destinations.failed("offline");
    });
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    h.assertTrue(LootCapture.drain(magnet, target, Long.MAX_VALUE), "empty pending queue drains");
    h.assertTrue(calls.get() == 0, "empty pending does not query destination");
    h.assertTrue(target.unavailable(), "first access resolves destination");
    target.insert(new ItemStack(Items.STONE), true);
    target.reason();
    h.assertTrue(calls.get() == 1, "destination resolved once per processing scope");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void standalone(GameTestHelper h) {
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    h.assertTrue(
        Destinations.get(p, 0).insert(new ItemStack(Items.DIAMOND, 64), false).isEmpty(),
        "standalone inventory works");
    h.assertTrue(
        Destinations.get(p, 1).insert(new ItemStack(Items.STONE), false).getCount() == 1,
        "missing RS refuses");
    h.assertTrue(
        Destinations.get(p, 2).insert(new ItemStack(Items.STONE), false).getCount() == 1,
        "missing backpack refuses");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void ordinarySource(GameTestHelper h) {
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    h.setBlock(1, 1, 1, Blocks.BARREL);
    var be = h.getBlockEntity(new BlockPos(1, 1, 1));
    h.assertTrue(
        Sources.eligible(be) && Sources.inventory(p, be) != null,
        "ordinary source without optional jars");
    h.succeed();
  }
}
