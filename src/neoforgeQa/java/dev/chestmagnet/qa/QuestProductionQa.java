package dev.chestmagnet.qa;

import dev.chestmagnet.ChestMagnet;
import dev.chestmagnet.Data;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;

final class QuestProductionQa {
  private QuestProductionQa() {}

  static void verify(ServerLevel level, ServerPlayer p) throws Exception {
    initTeam(p);
    var team = TeamData.get(p);
    var file = ServerQuestFile.INSTANCE;
    var group = file.getDefaultChapterGroup();
    var chapter = new Chapter(128000L, file, group, "neo_qa");
    group.addChapter(chapter);
    var named = new ItemStack(Items.PAPER);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Target"));
    var decoy = new ItemStack(Items.PAPER, 20);
    decoy.set(DataComponents.CUSTOM_NAME, Component.literal("Decoy"));
    var tasks = List.of(
        task(p, chapter, 128010, new ItemStack(Items.EMERALD), false, false),
        task(p, chapter, 128020, named, false, true),
        task(p, chapter, 128030, new ItemStack(Items.IRON_INGOT), true, false));
    var refresh = dev.ftb.mods.ftbquests.quest.BaseQuestFile.class.getDeclaredMethod("refreshIDMap");
    refresh.setAccessible(true);
    refresh.invoke(file);
    file.clearCachedData();
    require(tasks.get(1).test(named) && !tasks.get(1).test(decoy), "FTB native component match fixture");
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    Data.edit(magnet, t -> { t.putBoolean("Enabled", false); t.putBoolean("QuestDetection", false); });
    p.getInventory().setItem(0, magnet);
    var backpack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:backpack")));
    p.getInventory().setItem(7, backpack);
    var stock = BackpackWrapper.fromStack(backpack).getInventoryHandler();
    stock.setStackInSlot(0, new ItemStack(Items.EMERALD, 8));
    stock.setStackInSlot(1, decoy);
    stock.setStackInSlot(2, named.copyWithCount(3));
    stock.setStackInSlot(3, new ItemStack(Items.IRON_INGOT, 15));

    var detector = Class.forName("dev.chestmagnet.QuestDetection");
    var carried = detector.getDeclaredMethod("carried", ServerPlayer.class);
    carried.setAccessible(true);
    require(((ItemStack)carried.invoke(null, p)).isEmpty(), "quest toggle off is honored");
    Data.edit(magnet, t -> t.putBoolean("QuestDetection", true));
    require(carried.invoke(null, p) == magnet, "collection disabled does not disable detection");
    var scan = Class.forName("dev.chestmagnet.QuestDetection$Scan");
    var constructor = scan.getDeclaredConstructor();
    constructor.setAccessible(true);
    var session = constructor.newInstance();
    Method step = Class.forName("dev.chestmagnet.QuestCompat").getDeclaredMethod("step", ServerPlayer.class, scan, long.class);
    step.setAccessible(true);
    for (int i = 0; i < 40; i++) {
      if (!(boolean)step.invoke(null, p, session, System.nanoTime() + 20_000_000)) break;
    }
    var found = tasks.stream().map(team::getProgress).toList();
    require(found.equals(List.of(8L, 3L, 0L)), "ordinary, component and consumable task results: " + found);
    require(stock.getStackInSlot(0).getCount() == 8 && stock.getStackInSlot(1).getCount() == 20
        && stock.getStackInSlot(2).getCount() == 3 && stock.getStackInSlot(3).getCount() == 15,
        "FTB detection never consumes storage");
    p.getInventory().setItem(0, ItemStack.EMPTY);
    require(((ItemStack)carried.invoke(null, p)).isEmpty(), "removing magnet stops detection");
    NeoForgeProductionQa.LOG.info("QA_NEOFORGE_FTB ordinary=8 exact_components=3 consume=0 stock_unchanged");
  }

  @SuppressWarnings("unchecked")
  private static void initTeam(ServerPlayer p) throws Exception {
    // Use the two native local team creation steps, omitting the network sync on this headless fixture.
    var mgr = TeamManagerImpl.INSTANCE;
    var create = TeamManagerImpl.class.getDeclaredMethod("createPlayerTeam", java.util.UUID.class, String.class);
    create.setAccessible(true);
    var playerTeam = create.invoke(mgr, p.getUUID(), p.getGameProfile().getName());
    for (String name : List.of("knownPlayers", "teamMap")) {
      var field = TeamManagerImpl.class.getDeclaredField(name);
      field.setAccessible(true);
      ((java.util.Map<java.util.UUID, Object>) field.get(mgr)).put(p.getUUID(), playerTeam);
    }
    var base = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeam");
    var onCreated = base.getDeclaredMethod("onCreated", ServerPlayer.class, java.util.UUID.class);
    onCreated.setAccessible(true);
    onCreated.invoke(playerTeam, p, p.getUUID());
  }

  private static ItemTask task(ServerPlayer p, Chapter chapter, long id, ItemStack stack, boolean consumes, boolean exact) {
    var quest = new Quest(id, chapter);
    chapter.addQuest(quest);
    var task = new ItemTask(id + 1, quest);
    var tag = new CompoundTag();
    tag.put("item", stack.save(p.registryAccess()));
    tag.putLong("count", 64);
    tag.putBoolean("consume_items", consumes);
    if (exact) tag.putString("match_components", "strict");
    task.readData(tag, p.registryAccess());
    quest.addTask(task);
    require(task.consumesResources() == consumes, "native FTB task consumption flag");
    return task;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
