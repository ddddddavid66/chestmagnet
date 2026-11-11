package dev.chestmagnet;

import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class LockedTests {
  @GameTest(template = "empty")
  public static void lockAndFrames(GameTestHelper h) {
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    h.setBlock(1, 1, 1, Blocks.BARREL);
    var b = (BarrelBlockEntity) h.getBlockEntity(new BlockPos(1, 1, 1));
    var tag = b.saveWithoutMetadata();
    tag.putString("Lock", "test");
    b.load(tag);
    b.setItem(0, new ItemStack(Items.DIAMOND, 4));
    var inv = Sources.inventory(p, b, m);
    h.assertTrue(!SourceExtras.finish(p, b, inv, m), "not unlock nonempty");
    Transfer.move(inv, 0, 4, Destinations.get(p, 0), m);
    h.assertTrue(SourceExtras.finish(p, b, inv, m) && b.canOpen(p), "unlock empty barrel");
    var f = new ItemFrame(EntityType.ITEM_FRAME, h.getLevel());
    f.setItem(new ItemStack(Items.EMERALD));
    f.setInvisible(true);
    var fi = Sources.inventory(p, f, m);
    h.assertTrue(
        fi.extractItem(0, 1, true).getCount() == 1 && !f.getItem().isEmpty(),
        "simulate does not empty");
    Transfer.move(fi, 0, 1, Destinations.get(p, 0), m);
    h.assertTrue(f.getItem().isEmpty() && f.isInvisible(), "frame stays empty/invisible");
    Data.tag(m).putBoolean("CollectFrames", false);
    h.assertTrue(Sources.inventory(p, f, m) == null, "frame toggle");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void optionalLocked(GameTestHelper h) {
    if (!net.minecraftforge.fml.ModList.get().isLoaded("aether")) {
      h.succeed();
      return;
    }
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, m);
    var at = h.absolutePos(new BlockPos(1, 1, 1));
    var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aether:treasure_chest"));
    h.getLevel().setBlock(at, block.defaultBlockState(), 3);
    var b = h.getLevel().getBlockEntity(at);
    ((net.minecraft.world.Container) b).setItem(0, new ItemStack(Items.DIAMOND));
    var inv = Sources.inventory(p, b, m);
    h.assertTrue(Sources.eligible(b), "aether recognized");
    h.assertTrue(!SourceExtras.finish(p, b, inv, m), "aether partial stays locked");
    Transfer.move(inv, 0, 1, Destinations.get(p, 0), m);
    h.assertTrue(
        SourceExtras.finish(p, b, inv, m)
            && !(boolean) SourceExtras.call(b, "getLocked", new Class[] {}),
        "aether unlocked");
    var frozen = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("aquamirae:frozen_chest"));
    h.getLevel().setBlock(at, frozen.defaultBlockState(), 3);
    var special = new SourceExtras.Special(at);
    var frozenInv = Sources.inventory(p, special, m);
    h.assertTrue(
        h.getLevel().getBlockState(at).is(Blocks.CHEST) && frozenInv != null, "frozen transforms");
    var crate = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tetra:forged_crate"));
    h.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
    h.getLevel().setBlock(at, crate.defaultBlockState(), 3);
    Sources.inventory(p, new SourceExtras.Special(at), m);
    h.assertTrue(!h.getLevel().getBlockState(at).is(crate), "crate shell removed");
    h.assertTrue(LootCapture.pending(m), "crate loot captured");
    h.assertTrue(
        LootCapture.drain(m, Destinations.get(p, 0), Long.MAX_VALUE), "crate loot drained");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void forgedDouble(GameTestHelper h) {
    if (!net.minecraftforge.fml.ModList.get().isLoaded("tetra")) {
      h.succeed();
      return;
    }
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, m);
    var at = h.absolutePos(new BlockPos(2, 1, 2));
    var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("tetra:forged_container"));
    var state =
        block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
    h.getLevel().setBlock(at, state, 3);
    block.setPlacedBy(h.getLevel(), at, state, p, m);
    var be = h.getLevel().getBlockEntity(at);
    var tag = be.saveWithoutMetadata();
    tag.putInt("lid_integrity", 1);
    for (int n = 0; n < 4; n++) tag.putInt("lock_integrity" + n, 1);
    be.load(tag);
    var inv = Sources.inventory(p, be, m);
    h.assertTrue(LootCapture.pending(m), "lock rewards captured instead of inventory");
    h.assertTrue(Sources.of(be).members(p).size() == 2, "pair exclusion");
    LootCapture.drain(m, Destinations.get(p, 0), Long.MAX_VALUE);
    for (int slot = 0; slot < inv.getSlots(); slot++) {
      var sample = inv.extractItem(slot, 64, true);
      if (!sample.isEmpty()) Transfer.move(inv, slot, sample.getCount(), Destinations.get(p, 0), m);
    }
    h.assertTrue(
        !SourceExtras.finish(p, be, inv, m) && LootCapture.pending(m),
        "finish removes or queues salvage");
    h.assertTrue(
        !h.getLevel().getBlockState(at).is(block)
            && !h.getLevel().getBlockState(at.relative(Direction.EAST)).is(block),
        "both halves removed");
    h.assertTrue(LootCapture.drain(m, Destinations.get(p, 0), Long.MAX_VALUE), "salvage accepted");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void pendingAndMap(GameTestHelper h) {
    var p = FakePlayerFactory.getMinecraft(h.getLevel());
    p.getInventory().clearContent();
    var m = new ItemStack(ChestMagnet.MAGNET.get());
    LootCapture.run(
        m,
        () -> {
          LootCapture.add(new ItemStack(Items.DIAMOND, 7));
          LootCapture.add(new ItemStack(Items.EMERALD, 5));
          return null;
        });
    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
    h.assertTrue(
        !LootCapture.drain(m, Destinations.get(p, 0), Long.MAX_VALUE)
            && Data.tag(m).getList("PendingLoot", 10).size() == 2,
        "full destination preserves all stacks");
    var restored = ItemStack.of(m.save(new net.minecraft.nbt.CompoundTag()));
    p.getInventory().clearContent();
    h.assertTrue(
        LootCapture.drain(restored, Destinations.get(p, 0), Long.MAX_VALUE),
        "saved pending resumes");
    var f = new GlowItemFrame(EntityType.GLOW_ITEM_FRAME, h.getLevel());
    var ft = new net.minecraft.nbt.CompoundTag();
    f.addAdditionalSaveData(ft);
    ft.putBoolean("Fixed", true);
    f.readAdditionalSaveData(ft);
    var map = MapItem.create(h.getLevel(), 0, 0, (byte) 0, true, true);
    f.setItem(map);
    var inv = Sources.inventory(p, f, m);
    var original = inv.extractItem(0, 1, false);
    h.assertTrue(f.getItem().isEmpty(), "fixed map removed");
    h.assertTrue(
        inv.insertItem(0, original, false).isEmpty() && f.getFramedMapId().isPresent(),
        "map rollback rebinds");
    h.succeed();
  }
}
