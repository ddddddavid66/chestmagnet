package dev.chestmagnet;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class FaultTests {
  @GameTest(template = "empty")
  public static void isolateBatch(GameTestHelper h) {
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    var list = new ListTag();
    list.add(new ItemStack(Items.DIAMOND, 3).save(new CompoundTag()));
    list.add(new ItemStack(Items.EMERALD, 2).save(new CompoundTag()));
    Data.tag(m).put("PendingLoot", list);
    var bad =
        new Destinations.Target() {
          public ItemStack insert(ItemStack s, boolean sim) {
            if (sim) return ItemStack.EMPTY;
            throw new IllegalStateException("injected receive failure");
          }
        };
    try {
      LootCapture.drain(m, bad, Long.MAX_VALUE);
      throw new AssertionError("must throw");
    } catch (IllegalStateException expected) {
    }
    h.assertTrue(
        Data.tag(m).getList("Quarantined", 10).size() == 2,
        "uncertain and untouched batches sealed separately");
    h.assertTrue(!LootCapture.pending(m), "fault source does not keep blocking queue");
    h.assertTrue(!Data.tag(m).getBoolean("PendingUncertain"), "no global poison");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void knownPreparationCancel(GameTestHelper h) {
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    try {
      LootCapture.run(
          m,
          () -> {
            throw new IllegalStateException("cancel before loot");
          });
    } catch (IllegalStateException expected) {
    }
    h.assertTrue(
        !Data.tag(m).getBoolean("PendingUncertain") && !LootCapture.pending(m),
        "no reward or poison on prepare failure");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void specialPosition(GameTestHelper h) {
    var j = new StructureJobs.Job();
    j.region = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, -64, 0, 32, 320, 32);
    h.assertTrue(
        StructureJobs.contains(j, new SourceExtras.Special(new BlockPos(2, 10, 2))),
        "Special uses position, not BE cast");
    h.succeed();
  }
}
