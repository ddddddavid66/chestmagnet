package dev.chestmagnet;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

/** 仅验证此次事故涉及的拒绝、双半身份及捕获边界，不重复跑无关玩法。 */
@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class TetraSafetyTests {
  private static Block tetra(String id) {
    return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tetra", id));
  }

  private static boolean installed() {
    return net.minecraftforge.fml.ModList.get().isLoaded("tetra");
  }

  private static BlockEntity pair(GameTestHelper h, BlockPos at) {
    var block = tetra("forged_container");
    var state =
        block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
    h.getLevel().setBlock(at, state, 3);
    block.setPlacedBy(h.getLevel(), at, state, null, ItemStack.EMPTY);
    return h.getLevel().getBlockEntity(at);
  }

  private static final class Deny {
    final ServerLevel level;
    final BlockPos denied;
    int seen;

    Deny(ServerLevel level, BlockPos denied) {
      this.level = level;
      this.denied = denied;
    }

    @SubscribeEvent
    public void deny(BlockEvent.BreakEvent event) {
      if (event.getLevel() == level && event.getPos().equals(denied)) {
        seen++;
        event.setCanceled(true);
      }
    }
  }

  @GameTest(template = "empty")
  public static void rejectedCrateDoesNotPoison(GameTestHelper h) {
    if (!installed()) {
      h.succeed();
      return;
    }
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    p.setGameMode(GameType.SURVIVAL);
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    p.setItemInHand(InteractionHand.MAIN_HAND, magnet);
    var at = h.absolutePos(new BlockPos(1, 1, 1));
    h.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
    h.getLevel().setBlock(at, tetra("forged_crate").defaultBlockState(), 3);
    var before = h.getLevel().getBlockState(at);
    var denied = new Deny(h.getLevel(), at);
    MinecraftForge.EVENT_BUS.register(denied);
    boolean refused = false;
    try {
      Sources.inventory(p, new SourceExtras.Special(at), magnet);
    } catch (SourceExtras.KnownFailure expected) {
      refused = true;
    } finally {
      MinecraftForge.EVENT_BUS.unregister(denied);
    }
    h.assertTrue(refused && denied.seen == 1, "known cancellation reported once");
    h.assertTrue(h.getLevel().getBlockState(at) == before, "rejected crate preserved");
    h.assertTrue(
        !LootCapture.pending(magnet) && !Data.tag(magnet).getBoolean("PendingUncertain"),
        "no generated or uncertain crate rewards");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void secondHalfProtectionAndReplacement(GameTestHelper h) {
    if (!installed()) {
      h.succeed();
      return;
    }
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    p.setGameMode(GameType.SURVIVAL);
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    p.setItemInHand(InteractionHand.MAIN_HAND, magnet);
    var at = h.absolutePos(new BlockPos(2, 1, 2));
    var main = pair(h, at);
    var other = at.relative(Direction.EAST);
    var inv = Sources.inventory(p, main, magnet);
    var denied = new Deny(h.getLevel(), other);
    MinecraftForge.EVENT_BUS.register(denied);
    boolean refused = false;
    try {
      SourceExtras.finish(p, main, inv, magnet);
    } catch (SourceExtras.KnownFailure expected) {
      refused = true;
    } finally {
      MinecraftForge.EVENT_BUS.unregister(denied);
    }
    h.assertTrue(refused && denied.seen == 1, "second half permission is checked");
    h.assertTrue(
        h.getLevel().getBlockState(at).is(tetra("forged_container"))
            && h.getLevel().getBlockState(other).is(tetra("forged_container")),
        "neither half removed");
    // 保持方块不变，只替换另一半BE，复现收尾不能仅凭同registry id判断身份。
    var state = h.getLevel().getBlockState(other);
    h.getLevel().removeBlockEntity(other);
    h.getLevel().setBlockEntity(((EntityBlock) state.getBlock()).newBlockEntity(other, state));
    refused = false;
    try {
      SourceExtras.finish(p, main, inv, magnet);
    } catch (SourceExtras.KnownFailure expected) {
      refused = true;
    }
    h.assertTrue(
        refused && h.getLevel().getBlockState(at).is(tetra("forged_container")),
        "replacement half cannot be removed by stale inventory session");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void malformedPairAndToolContext(GameTestHelper h) {
    if (!installed()) {
      h.succeed();
      return;
    }
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    var at = h.absolutePos(new BlockPos(2, 1, 2));
    var main = pair(h, at);
    var other = at.relative(Direction.EAST);
    var state = h.getLevel().getBlockState(other);
    for (Property<?> prop : state.getProperties())
      if (prop.getName().equals("flipped")) state = state.setValue((Property<Boolean>) prop, false);
    h.getLevel().setBlock(other, state, 3);
    boolean refused = false;
    try {
      Sources.inventory(p, main, magnet);
    } catch (SourceExtras.KnownFailure expected) {
      refused = true;
    }
    h.assertTrue(refused && !LootCapture.pending(magnet), "malformed pair never opens");
    var pick = new ItemStack(Items.DIAMOND_PICKAXE);
    pick.enchant(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH, 1);
    p.setItemInHand(InteractionHand.MAIN_HAND, pick);
    var loot =
        SourceExtras.tetraLoot(
            new ResourceLocation("tetra:blocks/forged_container"),
            p,
            magnet,
            at,
            main.getBlockState());
    h.assertTrue(
        !loot.isEmpty() && loot.stream().noneMatch(s -> s.is(tetra("forged_container").asItem())),
        "inventory magnet does not borrow held silk touch");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void captureStaysWithinSource(GameTestHelper h) {
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    var at = h.absolutePos(new BlockPos(2, 1, 2));
    LootCapture.run(
        magnet,
        p,
        List.of(at),
        () -> {
          Block.popResource(h.getLevel(), at, new ItemStack(Items.DIAMOND, 3));
          h.assertTrue(
              !LootCapture.add(h.getLevel(), at.offset(4, 0, 0), new ItemStack(Items.EMERALD)),
              "unrelated callback drop not captured");
          return null;
        });
    var list = Data.tag(magnet).getList("PendingLoot", 10);
    h.assertTrue(
        list.size() == 1 && ItemStack.of(list.getCompound(0)).getCount() == 3,
        "only source reward captured");
    h.succeed();
  }
}
