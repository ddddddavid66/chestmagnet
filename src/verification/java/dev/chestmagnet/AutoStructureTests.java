package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class AutoStructureTests {
  private static CompoundTag result(GameTestHelper h) {
    var result = new CompoundTag();
    result.putInt("Version", 1); result.putString("State", "FOUND");
    result.putUUID("Revision", UUID.randomUUID());
    result.putString("Dimension", h.getLevel().dimension().location().toString());
    result.putString("Structure", "minecraft:desert_pyramid");
    result.putInt("StartX", 12); result.putInt("StartZ", -8);
    return result;
  }

  @GameTest(template = "empty")
  public static void playerHistorySurvivesResearchAndMagnetChange(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "AutoHistory"));
    var first = new ItemStack(ChestMagnet.MAGNET.get());
    h.assertTrue(!Data.autoStructure(first), "old and new magnets default to no automatic confirmation");
    var result = result(h);
    AutoStructures.remember(p, result, "COMPLETE", "fixture complete");
    var research = result.copy(); research.putUUID("Revision", UUID.randomUUID());
    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ChestMagnet.MAGNET.get()));
    h.assertTrue(AutoStructures.state(p, research).equals("COMPLETE"),
        "same exact instance survives new revision and changed magnet");
    var clone = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "AutoClone"));
    AutoStructures.copy(p, clone);
    h.assertTrue(AutoStructures.state(clone, research).equals("COMPLETE"), "clone copies server history");
    research.putInt("StartX", 13);
    h.assertTrue(AutoStructures.state(p, research).isEmpty(), "different instance remains eligible");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void blockedHistoryRequiresExplicitContinue(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "AutoBlocked"));
    var result = result(h);
    AutoStructures.remember(p, result, "BLOCKED", "fixture cancellation");
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    Data.tag(m).putBoolean("AutoStructure", false);
    Data.tag(m).putBoolean("AutoStructure", true);
    h.assertTrue(AutoStructures.state(p, result).equals("BLOCKED"),
        "toggling auto flag never clears cancelled or faulted structure history");
    AutoStructures.allow(p, result);
    h.assertTrue(AutoStructures.state(p, result).isEmpty(), "explicit continuation can unblock");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void automaticInventoryLookupAndRealTimeDeadline(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "AutoInventory"));
    p.getInventory().clearContent();
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    p.getInventory().setItem(5, m);
    var j = new StructureJobs.Job();
    j.magnet = Data.id(m); j.automatic = true;
    h.assertTrue(StructureJobs.item(p, j) == m, "automatic job finds active magnet without holding it");
    j.automatic = false;
    h.assertTrue(StructureJobs.item(p, j).isEmpty(), "manual job still requires holding matching magnet");
    j.waitStartedNanos = 1_000_000_000L;
    h.assertTrue(!StructureJobs.targetWaitExpired(j, 15_999_999_999L), "less than real fifteen seconds waits");
    h.assertTrue(StructureJobs.targetWaitExpired(j, 16_000_000_000L), "real fifteen seconds reaches deadline");
    var diamond = new ItemStack(Items.DIAMOND);
    h.assertTrue(!StructureJobs.canReceive(Destinations.failed("offline"), diamond),
        "offline target cannot resume or acquire any source tickets");
    h.assertTrue(!StructureJobs.canReceive(new Destinations.Target() {
      public ItemStack insert(ItemStack stack, boolean simulate) {
        h.assertTrue(simulate, "capacity retry is always simulation");
        return stack;
      }
    }, diamond), "full or filtering target stays paused without touching source chunks");
    h.assertTrue(StructureJobs.canReceive(new Destinations.Target() {
      public ItemStack insert(ItemStack stack, boolean simulate) {
        h.assertTrue(simulate, "capacity recovery is simulation only");
        return ItemStack.EMPTY;
      }
    }, diamond), "recovered target can resume existing progress");
    j.waitingTarget = j.paused = true;
    j.waitSample = diamond;
    j.waitStartedNanos = 1_000_000_000L;
    j.nextTargetCheck = 20;
    int[] calls = {0};
    java.util.function.Supplier<Destinations.Target> unavailable = () -> {
      calls[0]++; return Destinations.failed("full fixture");
    };
    StructureJobs.serviceTargetWait(j, unavailable, 19, 2_000_000_000L);
    h.assertTrue(calls[0] == 0 && j.paused, "retry is not checked before twenty ticks");
    StructureJobs.serviceTargetWait(j, unavailable, 20, 2_000_000_000L);
    StructureJobs.serviceTargetWait(j, unavailable, 21, 3_000_000_000L);
    h.assertTrue(calls[0] == 1 && j.paused && j.tickets.isEmpty(), "full target waits twenty ticks without tickets");
    boolean expired = StructureJobs.serviceTargetWait(j, unavailable, 40, 16_000_000_000L);
    h.assertTrue(expired && j.paused && !j.waitingTarget && calls[0] == 1,
        "timeout precedes last possible retry and stays paused");
    h.succeed();
  }
}
