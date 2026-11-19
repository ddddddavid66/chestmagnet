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
  private static net.minecraft.world.level.block.entity.BarrelBlockEntity remoteBarrel, localBarrel;
  private static List<ItemTask> tasks;
  private static List<ItemTask> extras = List.of();
  private static final boolean QA127 = Boolean.getBoolean("chestmagnet.qa127");
  private static int phase, elapsed, age;

  static void tick(ServerPlayer p) throws ReflectiveOperationException {
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
      config.putBoolean("QuestDetection", true);
      NearbyProductionChecks.questFramePhase = "on";
      next();
    } else if (phase == 3) {
      expect(team, 8, 9, 10);
      require(!config.getBoolean("Enabled") && localBarrel.getItem(0).getCount() == 6,
          "FTB progresses with collection disabled without touching a nearby source");
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
      if (Boolean.getBoolean("chestmagnet.inkProbe") && !InkProbe.tick(p, rs, beyond, bagA, magnet)) return;
      int bagCount = bagA.getStackInSlot(0).getCount() + bagB.getStackInSlot(4).getCount();
      long rsCount = rs.getItemStorageCache().getList().getStacks(new ItemStack(Items.DIAMOND)).stream()
          .mapToLong(entry -> entry.getStack().getCount()).sum();
      long bdCount = beyond.getUnifiedStorage().getStackByKey(new ItemStackKey(new ItemStack(Items.GOLD_INGOT))).amount();
      require(bagCount == 12 && rsCount == 13 && bdCount == 14,
          "task detection never consumes storage: backpack=" + bagCount + " RS=" + rsCount + " BD=" + bdCount);
      require(p.inventoryMenu == p.containerMenu, "task detection never opens GUI");
      NearbyProductionChecks.questFramePhase = "";
      QuestTiming.report();
      NearbyProductionChecks.LOG.info("QA126_FTB_PASS collection OFF still detects; quest OFF and removal stop; offhand resumes; no duplication");
      if (QA127) {
        var extraProgress = extras.stream().map(team::getProgress).toList();
        require(extraProgress.equals(List.of(0L, 1L, 0L, 3L, 1L, 2L, 1L, 4L)), "scroll/filter/late/nested: " + extraProgress);
        NearbyProductionChecks.LOG.info("QA127_QUEST_PASS wrong/correct/strict scroll, OR, tag, >128 variants, Inception");
      }
      config.putInt("Mode", 3);
      Class.forName("dev.chestmagnet.Engine").getMethod("toggle", ServerPlayer.class, ItemStack.class)
          .invoke(null, p, magnet);
      require(config.getBoolean("Enabled"), "collection toggle is independent");
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
      if (QA127) {
        p.setItemInHand(InteractionHand.MAIN_HAND, magnet);
        var at = p.blockPosition().north();
        p.serverLevel().setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
        var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) p.serverLevel().getBlockEntity(at);
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        net.minecraft.world.item.EnchantedBookItem.addEnchantment(book,
            new net.minecraft.world.item.enchantment.EnchantmentInstance(
                net.minecraft.world.item.enchantment.Enchantments.MENDING, 1));
        barrel.setItem(0, book);
        for (int i = 1; i <= 10; i++) barrel.setItem(i, new ItemStack(List.of(Items.DIRT, Items.STONE,
            Items.IRON_INGOT, Items.DIAMOND_HELMET, Items.BREAD, Items.BONE, Items.STRING,
            Items.APPLE, Items.POTATO, Items.BRICK).get(i - 1)));
        config.putInt("Mode", 0);
        next();
      } else NearbyProductionChecks.done = true;
    } else if (phase == 11 && elapsed == 20) {
      var at = p.blockPosition().south();
      p.serverLevel().setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
      var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) p.serverLevel().getBlockEntity(at);
      barrel.setItem(0, new ItemStack(Items.ECHO_SHARD, 4));
      config.putInt("Mode", 2);
    } else if (phase == 11 && elapsed >= 120) {
      var id = config.getUUID("Id");
      net.minecraftforge.network.NetworkHooks.openScreen(p, new net.minecraft.world.SimpleMenuProvider(
          (n, inv, owner) -> new dev.chestmagnet.MagnetMenu(n, inv, false, id),
          net.minecraft.network.chat.Component.literal("宝箱磁铁")),
          buf -> { buf.writeBoolean(false); buf.writeUUID(id); });
      dev.chestmagnet.Net.sync(p, magnet, "QA127");
      var records = p.getPersistentData().getList("ChestMagnetRecentItems", 10);
      var saved = new CompoundTag();
      p.saveWithoutId(saved);
      var loaded = net.minecraftforge.common.util.FakePlayerFactory.get(p.serverLevel(),
          new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Magnet127Reload"));
      loaded.load(saved);
      require(loaded.getPersistentData().getList("ChestMagnetRecentItems", 10).equals(records),
          "player NBT reload preserves recent records");
      require(records.stream().anyMatch(t -> {
        var row = (CompoundTag)t;
        var display = row.getCompound("Display");
        return display.getString("Item").equals("minecraft:echo_shard") && display.getInt("Mode") == 2
            && row.getLong("Count") == 4;
      }) && team.getProgress(tasks.get(4)) == 0, "voided stack logged as processed but did not advance quest");
      require(records.size() >= 12 && records.stream().anyMatch(t -> ((CompoundTag)t)
          .getCompound("Display").getString("Item").equals("minecraft:enchanted_book")), "actual nearby receipt logged");
      NearbyProductionChecks.LOG.info("QA127_RECENT_PASS partial-independent actual receipts, void result separated from quests, player NBT round-trip");
      next();
    } else if (phase == 12 && Production127Ui.finished) {
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
    String diskId=System.getProperty("chestmagnet.qaDisk", "");
    if(diskId.isEmpty())level.setBlockAndUpdate(CTRL.east(),block("1k_storage_block").defaultBlockState());
    else {
      level.setBlockAndUpdate(CTRL.east(),block("disk_drive").defaultBlockState());
      var drive=(com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity)level.getBlockEntity(CTRL.east());
      var disk=item(diskId);require(!disk.isEmpty(),"unknown test disk item "+diskId);disk.getItem().inventoryTick(disk,level,p,0,false);
      ((IItemHandlerModifiable)drive.getNode().getDisks()).setStackInSlot(0,disk);
      NearbyProductionChecks.LOG.info("INK_DISK_FIXTURE {}",diskId);
    }
    var access = (BaseBlock) block("wireless_transmitter");
    level.setBlockAndUpdate(CTRL.above(), access.defaultBlockState().setValue(access.getDirection().getProperty(), Direction.DOWN));
    rs = ((ControllerBlockEntity) level.getBlockEntity(CTRL)).getNetwork();
    rs.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
    rs.getNodeGraph().invalidate(Action.PERFORM, level, CTRL);
  }

  private static void bindAndSeed(ServerPlayer p) {
    if(Boolean.getBoolean("chestmagnet.inkProbe"))InkProbe.emptyTasks(p);
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
    if (QA127) seedExtras(p, chapter);
    file.refreshIDMap();
    file.clearCachedData();
    for (var task : tasks) TeamData.get(p).resetProgress(task);
    for (var task : extras) TeamData.get(p).resetProgress(task);
    verifyVoidedItem(p);
    magnet = item("chestmagnet:chest_magnet");
    config = magnet.getOrCreateTagElement("ChestMagnet");
    config.putBoolean("Enabled", false);
    config.putBoolean("QuestDetection", false);
    p.getInventory().setItem(35, magnet);
    p.serverLevel().setBlockAndUpdate(STAND.east(), Blocks.BARREL.defaultBlockState());
    localBarrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity)
        p.serverLevel().getBlockEntity(STAND.east());
    localBarrel.setItem(0, new ItemStack(Items.COAL, 6));
  }

  private static ItemTask task(Chapter chapter, long id, Item item, boolean consume) {
    return task(chapter, id, new ItemStack(item), consume, false);
  }

  private static ItemTask task(Chapter chapter, long id, ItemStack sample, boolean consume, boolean exact) {
    var quest = new Quest(id, chapter);
    chapter.addQuest(quest);
    var task = new ItemTask(id + 1, quest);
    var tag = new CompoundTag();
    dev.ftb.mods.ftbquests.util.NBTUtils.write(tag, "item", sample);
    tag.putLong("count", 64);
    tag.putBoolean("consume_items", consume);
    if (exact) tag.putBoolean("match_nbt", true);
    task.readData(tag);
    require(task.getItemStack().is(sample.getItem()) && task.consumesResources() == consume, "native FTB task deserialization");
    quest.addTask(task);
    return task;
  }

  private static void seedExtras(ServerPlayer p, Chapter chapter) {
    var wrong = scroll("qa_wrong", 3);
    var actual = scroll("qa_schematic", 7);
    var expected = scroll("qa_schematic", 2);
    var tag = item("itemfilters:tag");
    tag.getOrCreateTag().putString("value", "minecraft:logs");
    var or = item("itemfilters:or");
    var children = new net.minecraft.nbt.ListTag();
    children.add(new ItemStack(Items.GLOWSTONE_DUST).save(new CompoundTag()));
    children.add(new ItemStack(Items.GLOWSTONE_DUST).save(new CompoundTag()));
    children.add(new ItemStack(Items.NETHER_STAR).save(new CompoundTag()));
    or.getOrCreateTag().put("items", children);
    var late = new ItemStack(Items.FLINT);
    late.getOrCreateTag().putInt("qa", 149);
    var lateRs = new ItemStack(Items.SHEARS);
    lateRs.getOrCreateTag().putInt("qa", 149);
    extras = List.of(task(chapter, 127010, wrong, false, false), task(chapter, 127020, expected, false, false),
        task(chapter, 127030, expected, false, true), task(chapter, 127040, tag, false, false),
        task(chapter, 127050, or, false, false), task(chapter, 127060, late, false, true),
        task(chapter, 127070, lateRs, false, true), task(chapter, 127080, Items.PRISMARINE_SHARD, false));
    rs.insertItem(actual, 1, Action.PERFORM);
    rs.insertItem(new ItemStack(Items.OAK_LOG, 3), 3, Action.PERFORM);
    beyond.getUnifiedStorage().insert(new ItemStackKey(new ItemStack(Items.GLOWSTONE_DUST)), 1, false);
    for (int i = 0; i < 150; i++) {
      var variant = new ItemStack(Items.FLINT);
      variant.getOrCreateTag().putInt("qa", i);
      beyond.getUnifiedStorage().insert(new ItemStackKey(variant), 2, false);
      var shears = new ItemStack(Items.SHEARS);
      shears.getOrCreateTag().putInt("qa", i);
      require(rs.insertItem(shears, 1, Action.PERFORM).isEmpty(), "RS variant inserted");
    }
    var cap = net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance();
    var outer = p.getInventory().getItem(19).getCapability(cap).orElseThrow(IllegalStateException::new);
    outer.getUpgradeHandler().setStackInSlot(0, item("sophisticatedbackpacks:inception_upgrade"));
    var inner = item("sophisticatedbackpacks:backpack");
    var storage = inner.getCapability(cap).orElseThrow(IllegalStateException::new);
    storage.getInventoryForInputOutput().insertItem(new ItemStack(Items.GLOWSTONE_DUST), false);
    bagB.setStackInSlot(8, inner);
    var onlyNested = item("sophisticatedbackpacks:backpack");
    onlyNested.getCapability(cap).orElseThrow(IllegalStateException::new).getInventoryForInputOutput()
        .insertItem(new ItemStack(Items.PRISMARINE_SHARD, 4), false);
    bagB.setStackInSlot(9, onlyNested);
    // 同一个子包也挂在物品栏，不能按两份计算。
    p.getInventory().setItem(22, inner.copy());
  }

  private static ItemStack scroll(String key, int glyph) {
    var stack = item("tetra:scroll_rolled");
    var row = new CompoundTag();
    row.putString("key", key);
    row.putIntArray("glyphs", new int[]{glyph});
    var effects = new net.minecraft.nbt.ListTag();
    effects.add(net.minecraft.nbt.StringTag.valueOf("tetra:" + key));
    row.put("effects", effects);
    var data = new net.minecraft.nbt.ListTag();
    data.add(row);
    stack.getOrCreateTagElement("BlockEntityTag").put("data", data);
    return stack;
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
