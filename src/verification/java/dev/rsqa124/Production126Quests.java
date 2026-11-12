package dev.rsqa124;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.block.BaseBlock;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;

final class Production126Quests {
  private static final BlockPos CTRL = new BlockPos(0, 120, 0);
  private static final BlockPos STAND = new BlockPos(3, 120, 3);
  private static ItemStack magnet;
  private static CompoundTag config;
  private static IItemHandlerModifiable bagA, bagB;
  private static DimensionsNet beyond, second;
  private static INetwork rs;
  private static net.minecraft.world.level.block.entity.BarrelBlockEntity remoteBarrel;
  private static List<ItemTask> tasks;
  private static int phase, elapsed, age;

  static void tick(ServerPlayer p) {
    if (++age > 1200) throw new IllegalStateException("FTB QA timed out at phase " + phase);
    if (phase == 0) { setup(p); next(); return; }
    rs.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    if (++elapsed < 80 && phase >= 2 && phase <= 8) return;
    var team = TeamData.get(p);
    if (phase == 1) {
      if (team == null || !rs.canRun() || rs.getItemStorageCache().getStorages().isEmpty()) return;
      bindAndSeed(p);
      NearbyProductionChecks.questFramePhase = "off";
      next();
    } else if (phase == 2) {
      expect(team, 0, 0, 0);
      config.putBoolean("Enabled", true);
      NearbyProductionChecks.questFramePhase = "on";
      next();
    } else if (phase == 3) {
      expect(team, 8, 9, 10);
      NearbyProductionChecks.questFramePhase = "";
      config.putBoolean("QuestDetection", false);
      changeStock(12, 13, 14);
      next();
    } else if (phase == 4) {
      expect(team, 8, 9, 10);
      config.putBoolean("QuestDetection", true);
      p.getInventory().setItem(35, ItemStack.EMPTY);
      next();
    } else if (phase == 5) {
      expect(team, 8, 9, 10);
      p.getInventory().setItem(40, magnet);
      next();
    } else if (phase == 6) {
      expect(team, 12, 13, 14);
      // 同一批物品挪走再放回，进度不按搬运次数增长。
      changeStock(3, 3, 3);
      next();
    } else if (phase == 7) {
      expect(team, 12, 13, 14);
      changeStock(12, 13, 14);
      next();
    } else if (phase == 8) {
      expect(team, 12, 13, 14);
      int bagCount = bagA.getStackInSlot(0).getCount() + bagB.getStackInSlot(4).getCount();
      long rsCount = rs.getItemStorageCache().getList().getStacks(new ItemStack(Items.DIAMOND)).stream()
          .mapToLong(entry -> entry.getStack().getCount()).sum();
      long bdCount = beyond.getUnifiedStorage().getStackByKey(new ItemStackKey(new ItemStack(Items.GOLD_INGOT))).amount();
      require(bagCount == 12 && rsCount == 13 && bdCount == 14,
          "task detection never consumes storage: backpack=" + bagCount + " RS=" + rsCount + " BD=" + bdCount);
      require(p.inventoryMenu == p.containerMenu, "task detection never opens GUI");
      NearbyProductionChecks.questFramePhase = "";
      QuestTiming.report();
      NearbyProductionChecks.LOG.info("QA126_FTB_PASS preexisting and manual stock, disabled gates, offhand, no duplication");
      config.putInt("Mode", 3);
      var remote = p.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
      var stand = new BlockPos(8, 120, 8);
      remote.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
      remote.setBlockAndUpdate(stand, Blocks.AIR.defaultBlockState());
      remote.setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
      p.teleportTo(remote, 8.5, 120, 8.5, 0, 0);
      remote.setBlockAndUpdate(stand.east(), Blocks.BARREL.defaultBlockState());
      remoteBarrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) remote.getBlockEntity(stand.east());
      remoteBarrel.setItem(0, new ItemStack(Items.LAPIS_LAZULI, 11));
      next();
    } else if (phase == 9 && remoteBarrel.isEmpty()) {
      var key = new ItemStackKey(new ItemStack(Items.LAPIS_LAZULI));
      require(beyond.getUnifiedStorage().getStackByKey(key).amount() == 11
          && p.inventoryMenu == p.containerMenu, "Nether barrel -> native Beyond primary, no GUI");
      var other = net.minecraftforge.common.util.FakePlayerFactory.get(p.serverLevel(),
          new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "BD126Second"));
      second = DimensionsNet.createNewNetForPlayer(other, 100L, 5);
      require(second != null && second != beyond, "native second network");
      require(!DimensionsNet.setPrimaryNetForPlayer(p, second), "non-member cannot switch");
      second.addPlayer(p.getUUID());
      DimensionsNet.setPrimaryNetForPlayer(p, second);
      require(DimensionsNet.getPrimaryNetFromPlayer(p) == second, "native selected second network");
      p.serverLevel().removeBlock(remoteBarrel.getBlockPos(), false);
      var at = p.blockPosition().west();
      p.serverLevel().setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
      remoteBarrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) p.serverLevel().getBlockEntity(at);
      remoteBarrel.setItem(0, new ItemStack(Items.LAPIS_LAZULI, 7));
      next();
    } else if (phase == 10 && remoteBarrel.isEmpty()) {
      var key = new ItemStackKey(new ItemStack(Items.LAPIS_LAZULI));
      require(beyond.getUnifiedStorage().getStackByKey(key).amount() == 11
          && second.getUnifiedStorage().getStackByKey(key).amount() == 7, "same magnet follows native primary switch");
      NearbyProductionChecks.LOG.info("QA126_BD_PASS Nether barrel -> primary network: 11; switched primary: 7");
      NearbyProductionChecks.done = true;
    }
  }

  private static void next() { phase++; elapsed = 0; }

  private static void setup(ServerPlayer p) {
    var level = p.serverLevel();
    p.getInventory().clearContent();
    p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    level.setBlockAndUpdate(STAND.below(), Blocks.STONE.defaultBlockState());
    p.teleportTo(3.5, 120, 3.5);
    level.setBlockAndUpdate(CTRL, block("controller").defaultBlockState());
    level.setBlockAndUpdate(CTRL.east(), block("1k_storage_block").defaultBlockState());
    var access = (BaseBlock) block("wireless_transmitter");
    level.setBlockAndUpdate(CTRL.above(), access.defaultBlockState().setValue(access.getDirection().getProperty(), Direction.DOWN));
    rs = ((ControllerBlockEntity) level.getBlockEntity(CTRL)).getNetwork();
    rs.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    rs.getNodeGraph().invalidate(Action.PERFORM, level, CTRL);
  }

  private static void bindAndSeed(ServerPlayer p) {
    var terminal = item("refinedstorage:wireless_grid");
    p.setItemInHand(InteractionHand.MAIN_HAND, terminal);
    var hit = new BlockHitResult(Vec3.atCenterOf(CTRL), Direction.UP, CTRL, false);
    require(terminal.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit)).consumesAction(), "native RS binding");
    terminal.getCapability(ForgeCapabilities.ENERGY).orElseThrow(IllegalStateException::new)
        .receiveEnergy(Integer.MAX_VALUE, false);
    bagA = bag(p, 4);
    bagB = bag(p, 19);
    beyond = DimensionsNet.createNewNetForPlayer(p, 100_000L, 1000);
    require(beyond != null, "native Beyond primary");
    beyond.getUnifiedStorage().insert(new ItemStackKey(new ItemStack(Items.IRON_INGOT)), 20, false);
    changeStock(8, 9, 10);
    // 填充无关物品；测试不应为了少数任务遍历全部仓库。
    for (int i = 0; i < 300; i++) {
      var paper = new ItemStack(Items.PAPER);
      paper.getOrCreateTag().putInt("qa", i);
      beyond.getUnifiedStorage().insert(new ItemStackKey(paper), 1, false);
    }
    var file = ServerQuestFile.INSTANCE;
    var group = file.getDefaultChapterGroup();
    var chapter = new Chapter(126000L, file, group, "qa126");
    group.addChapter(chapter);
    tasks = List.of(task(chapter, 126010, Items.EMERALD, false), task(chapter, 126020, Items.DIAMOND, false),
        task(chapter, 126030, Items.GOLD_INGOT, false), task(chapter, 126040, Items.IRON_INGOT, true),
        task(chapter, 126050, Items.ECHO_SHARD, false));
    file.refreshIDMap();
    file.clearCachedData();
    for (var task : tasks) TeamData.get(p).resetProgress(task);
    verifyVoidedItem(p);
    magnet = item("chestmagnet:chest_magnet");
    config = magnet.getOrCreateTagElement("ChestMagnet");
    config.putBoolean("Enabled", false);
    p.getInventory().setItem(35, magnet);
  }

  private static ItemTask task(Chapter chapter, long id, Item item, boolean consume) {
    var quest = new Quest(id, chapter);
    chapter.addQuest(quest);
    var task = new ItemTask(id + 1, quest);
    var tag = new CompoundTag();
    dev.ftb.mods.ftbquests.util.NBTUtils.write(tag, "item", new ItemStack(item));
    tag.putLong("count", 64);
    tag.putBoolean("consume_items", consume);
    task.readData(tag);
    require(task.getItemStack().is(item) && task.consumesResources() == consume, "native FTB task deserialization");
    quest.addTask(task);
    return task;
  }

  private static void changeStock(int bag, int rsCount, int bd) {
    bagA.setStackInSlot(0, new ItemStack(Items.EMERALD, Math.min(3, bag)));
    bagB.setStackInSlot(4, new ItemStack(Items.EMERALD, Math.max(0, bag - 3)));
    rs.extractItem(new ItemStack(Items.DIAMOND), 64, Action.PERFORM);
    require(rs.insertItem(new ItemStack(Items.DIAMOND, rsCount), rsCount, Action.PERFORM).isEmpty(), "native RS seed");
    var key = new ItemStackKey(new ItemStack(Items.GOLD_INGOT));
    beyond.getUnifiedStorage().extract(key, 64, false, false);
    require(beyond.getUnifiedStorage().insert(key, bd, false).amount() == 0, "native BD seed");
  }

  private static void expect(TeamData team, long bag, long rs, long bd) {
    var observed = tasks.stream().map(team::getProgress).toList();
    require(observed.equals(List.of(bag, rs, bd, 0L, 0L)), "FTB phase " + phase + " expected "
        + List.of(bag, rs, bd, 0L, 0L) + ", got " + observed);
  }

  private static void verifyVoidedItem(ServerPlayer p) {
    var cap = net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance();
    var wrapper = p.getInventory().getItem(4).getCapability(cap).orElseThrow(IllegalStateException::new);
    wrapper.getUpgradeHandler().setStackInSlot(0, item("sophisticatedbackpacks:void_upgrade"));
    var voider = wrapper.getUpgradeHandler().getWrappersThatImplement(
        net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper.class).get(0);
    try {
      var type = Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidType");
      voider.getClass().getMethod("setVoidType", type).invoke(voider, type.getField("ALWAYS").get(null));
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("native void upgrade setup failed", error);
    }
    voider.getFilterLogic().setEmptyAllowListMatchesEverything();
    require(dev.chestmagnet.Destinations.get(p, 2).insert(new ItemStack(Items.ECHO_SHARD, 4), false).isEmpty(),
        "native backpack consumed the voided stack");
    for (int slot = 0; slot < bagA.getSlots(); slot++)
      require(!bagA.getStackInSlot(slot).is(Items.ECHO_SHARD), "voided stack was not stored");
  }

  private static IItemHandlerModifiable bag(ServerPlayer p, int slot) {
    var stack = item("sophisticatedbackpacks:backpack");
    p.getInventory().setItem(slot, stack);
    return stack.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
        .orElseThrow(IllegalStateException::new).getInventoryHandler();
  }

  private static Block block(String id) {
    return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage", id));
  }

  private static ItemStack item(String id) {
    return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)));
  }

  private static void require(boolean result, String message) {
    Production125Compat.require(result, message);
  }
}
