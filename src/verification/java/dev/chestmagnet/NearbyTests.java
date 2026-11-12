package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class NearbyTests {
  @GameTest(template = "empty")
  public static void recipe125(GameTestHelper h) {
    var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, 0) {
      public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
        return ItemStack.EMPTY;
      }
      public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
    };
    var grid = new TransientCraftingContainer(menu, 3, 3);
    var ingredients = new net.minecraft.world.item.Item[] {Items.IRON_INGOT, Items.REDSTONE,
        Items.IRON_INGOT, Items.GOLD_INGOT, Items.CHEST, Items.GOLD_INGOT, Items.IRON_INGOT,
        Items.AMETHYST_SHARD, Items.IRON_INGOT};
    for (int i = 0; i < ingredients.length; i++) grid.setItem(i, new ItemStack(ingredients[i]));
    var recipe = h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, h.getLevel())
        .orElseThrow(() -> new IllegalStateException("magnet crafting recipe not found"));
    var stack = recipe.assemble(grid, h.getLevel().registryAccess());
    h.assertTrue(stack.is(ChestMagnet.MAGNET.get()) && stack.getCount() == 1, "one magnet");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void immediateNearby(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "Near125"));
    var at = h.absolutePos(new BlockPos(2, 1, 2));
    p.setPos(at.getX(), at.getY(), at.getZ());
    h.setBlock(2, 1, 2, Blocks.BARREL);
    var barrel = (BarrelBlockEntity) h.getBlockEntity(new BlockPos(2, 1, 2));
    barrel.setItem(26, new ItemStack(Items.EMERALD, 17));
    h.setBlock(2, 5, 2, Blocks.BARREL);
    var high = (BarrelBlockEntity) h.getBlockEntity(new BlockPos(2, 5, 2));
    high.setItem(0, new ItemStack(Items.DIAMOND, 7));
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    var engine = new Engine();
    var state = new Engine.State();
    engine.work(p, magnet, state, Long.MAX_VALUE);
    h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 17, "first work call discovers and transfers");
    h.assertTrue(high.getItem(0).getCount() == 7, "out of Y range untouched");
    p.setPos(at.getX(), at.getY() + 4, at.getZ());
    engine.work(p, magnet, state, Long.MAX_VALUE);
    h.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 7, "after movement new height scanned");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void partialProbeIsNotEmpty(GameTestHelper h) {
    var p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "NearSlots125"));
    var center = h.absolutePos(new BlockPos(2, 1, 2));
    p.setPos(center.getX(), center.getY(), center.getZ());
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    var state = new Engine.State();
    state.magnet = Data.id(magnet);
    state.dimension = p.level().dimension().location().toString();
    state.range = "8:3:8";
    for (int x = 1; x <= 3; x++) {
      h.setBlock(x, 1, 2, Blocks.BARREL);
      var barrel = (BarrelBlockEntity) h.getBlockEntity(new BlockPos(x, 1, 2));
      if (x == 3) barrel.setItem(26, new ItemStack(Items.EMERALD, 6));
      var source = Sources.of(barrel);
      state.sources.put(source.key(p), source);
    }
    var engine = new Engine();
    engine.work(p, magnet, state, Long.MAX_VALUE);
    h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 0, "third inventory visit truncated");
    engine.work(p, magnet, state, Long.MAX_VALUE);
    h.assertTrue(p.getInventory().countItem(Items.EMERALD) == 6, "next visit resumes without cooldown");
    h.succeed();
  }
}
