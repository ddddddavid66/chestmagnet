package dev.rsqa124;

import dev.chestmagnet.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = "rsqa124")
public final class Production128 {
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-qa128");
  private static String scenario = System.getProperty("chestmagnet.qa128", "");
  private static int stage;
  private static int ticks;
  private static BlockPos anchor;
  private static BlockPos container;
  private static ItemStack magnet;
  private static ItemStack compass;
  private static final ResourceLocation VILLAGE = new ResourceLocation("minecraft:village_plains");
  static volatile boolean done;

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent event) {
    if (done || scenario.isEmpty() || event.phase != TickEvent.Phase.END) return;
    var server = event.getServer();
    try {
      if (scenario.equals("screening")) {
        if (++ticks < 20) return;
        var level = server.overworld();
        var p = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ScanScreening"));
        var tool = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet")));
        tool.getOrCreateTagElement("ChestMagnet").putUUID("Id", UUID.randomUUID());
        Production127Bench.screen(level, p, tool);
        LOG.info("QA128_PASS screening");
        done = true;
        server.halt(false);
        return;
      }
      if (scenario.equals("minimal") || scenario.equals("quark")) {
        if (++ticks < 5) return;
        var level = server.overworld();
        var p = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "HistoryTester"));
        history(p);
        if (scenario.equals("quark")) quark(p);
        else {
          require("UNSUPPORTED".equals(CompassTargets.snapshot(p).getString("State")), "no compass remains optional");
          var at = level.getSharedSpawnPos().above();
          p.setPos(at.getX(), at.getY(), at.getZ());
          p.getInventory().setItem(0, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet"))));
          level.setBlockAndUpdate(at.east(), Blocks.BARREL.defaultBlockState());
          var barrel = (BarrelBlockEntity)level.getBlockEntity(at.east());
          barrel.setItem(0, new ItemStack(Items.POTATO, 11));
          var state = Class.forName("dev.chestmagnet.Engine$State");
          var constructor = state.getDeclaredConstructor();
          constructor.setAccessible(true);
          method("Engine", "work", ServerPlayer.class, ItemStack.class, state, long.class)
              .invoke(new Engine(), p, p.getInventory().getItem(0), constructor.newInstance(),
                  System.nanoTime() + 100_000_000L);
          require(barrel.isEmpty() && p.getInventory().countItem(Items.POTATO) == 11, "real nearby transfer without compass");
          LOG.info("QA128_NO_COMPASS_NEARBY_PASS 11");
        }
        LOG.info("QA128_PASS {}", scenario);
        done = true;
        server.halt(false);
        return;
      }
      var players = server.getPlayerList().getPlayers();
      if (players.isEmpty()) return;
      var p = players.get(0);
      if (scenario.equals("ui")) {
        if (stage++ == 0) ui(p);
        return;
      }
      compass(p);
    } catch (Throwable error) {
      LOG.error("QA128_FAILURE {}", scenario, error);
      done = true;
      if (server.isDedicatedServer()) server.halt(false);
    }
  }

  private static Method method(String type, String name, Class<?>... params) throws ReflectiveOperationException {
    var m = Class.forName("dev.chestmagnet." + type).getDeclaredMethod(name, params);
    m.setAccessible(true);
    return m;
  }

  private static CompoundTag display(String name, int category) {
    var result = new CompoundTag();
    result.putString("Item", name);
    result.putInt("Category", category);
    return result;
  }

  private static void history(ServerPlayer p) throws Exception {
    var append = method("RecentItems", "append", CompoundTag.class, CompoundTag.class, int.class, long.class, long.class);
    var page = method("RecentItems", "page", CompoundTag.class, int.class, int.class);
    var pools = new CompoundTag();
    var stone = display("minecraft:stone", 1);
    append.invoke(null, pools, stone.copy(), 0, 2L, 1_000L);
    append.invoke(null, pools, display("minecraft:enchanted_book", 2), 0, 1L, 2_000L);
    append.invoke(null, pools, stone.copy(), 2, 3L, 61_000L);
    append.invoke(null, pools, stone.copy(), 1, 1L, 61_001L);
    var rows = ((CompoundTag) page.invoke(null, pools, 1, 0)).getList("Rows", 10);
    require(rows.size() == 2 && rows.getCompound(1).getLong("Count") == 5
        && Arrays.equals(rows.getCompound(1).getLongArray("Destinations"), new long[]{2,0,3,0}), "interleaved merge and boundary");
    var variant = stone.copy();
    variant.putString("Fingerprint", "other");
    append.invoke(null, pools, variant, 0, 5L, 61_002L);
    require(((CompoundTag) page.invoke(null, pools, 1, 0)).getInt("Total") == 3, "variant remains distinct");
    for (int cat = 1; cat <= 5; cat++) {
      for (int i = 0; i < 260; i++) {
        var entry = display("fixture:category" + cat + "_" + i, cat);
        entry.putString("Fingerprint", "a".repeat(64));
        entry.putString("Name", "历史测试变体 " + cat + " / " + i);
        append.invoke(null, pools, entry, i % 4, 1L, 100_000L + (cat == 1 ? 10_000 : 0) + i);
      }
      require(((CompoundTag) page.invoke(null, pools, cat, 31)).getList("Rows", 10).size() == 8, "category keeps 256");
    }
    var oldestAll = (CompoundTag) page.invoke(null, pools, 0, 31);
    require(oldestAll.getInt("Total") == 256 && oldestAll.getList("Rows", 10).getCompound(7)
        .getCompound("Display").getString("Item").equals("fixture:category1_4"), "all view newest 256");
    var raw = new java.io.ByteArrayOutputStream();
    NbtIo.write(pools, new java.io.DataOutputStream(raw));
    var gzip = new java.io.ByteArrayOutputStream();
    NbtIo.writeCompressed(pools, gzip);
    for (int i = 0; i < 100; i++) page.invoke(null, pools, 0, 31);
    var timing = new long[512];
    for (int i = 0; i < timing.length; i++) {
      long start = System.nanoTime();
      page.invoke(null, pools, 0, 31);
      timing[i] = System.nanoTime() - start;
    }
    Arrays.sort(timing);
    LOG.info("QA128_HISTORY_CAP rows=1280 raw={} gzip={} worst_page_p50_ns={} worst_page_p95_ns={}",
        raw.size(), gzip.size(), timing[256], timing[486]);
    var legacy = new ListTag();
    var old = new CompoundTag();
    old.put("Display", display("minecraft:stone", 1));
    old.getCompound("Display").putInt("Mode", 3);
    old.putLong("Count", 5);
    old.putLong("Time", 101);
    legacy.add(old);
    p.getPersistentData().put("ChestMagnetRecentItems", legacy);
    var migrated = (CompoundTag) method("RecentItems", "page", ServerPlayer.class, int.class, int.class).invoke(null, p, 0, 0);
    require(migrated.getList("Rows", 10).getCompound(0).getLongArray("Destinations")[3] == 5
        && !p.getPersistentData().contains("ChestMagnetRecentItems"), "legacy migration");
    var saved = new CompoundTag();
    p.saveWithoutId(saved);
    var fresh = FakePlayerFactory.get(p.serverLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "HistoryClone"));
    fresh.load(saved);
    require(fresh.getPersistentData().getCompound("ChestMagnetRecentPools")
        .equals(p.getPersistentData().getCompound("ChestMagnetRecentPools")), "save and load history");
  }

  private static void quark(ServerPlayer p) throws Exception {
    var level = p.serverLevel();
    var inv = method("Sources", "inventory", ServerPlayer.class, Object.class, ItemStack.class);
    var at = new BlockPos(0, 110, 4);
    var left = at.offset(15, 0, 0);
    var right = left.east();
    var chest = block("quark:spruce_chest").defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.NORTH);
    level.getChunkAt(left);
    level.getChunkAt(right);
    level.setBlock(left, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    level.setBlock(right, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    var l = (ChestBlockEntity) level.getBlockEntity(left);
    var r = (ChestBlockEntity) level.getBlockEntity(right);
    l.setItem(0, new ItemStack(Items.DIAMOND, 2));
    r.setItem(0, new ItemStack(Items.EMERALD, 3));
    var pair = (IItemHandler) inv.invoke(null,p,l,null);
    require(pair != null && pair.getSlots() == 54, "cross chunk double Quark chest");
    require(pair.extractItem(0, 64, false).getCount() + pair.extractItem(27, 64, false).getCount() == 5, "both halves extracted");
    var trap = block("quark:jungle_trapped_chest");
    level.setBlockAndUpdate(at, trap.defaultBlockState());
    var te = (ChestBlockEntity) level.getBlockEntity(at);
    te.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
    require(((IItemHandler) inv.invoke(null,p,te,null)).extractItem(0,2,false).getCount() == 2 && te.getItem(0).getCount() == 1, "Quark trap partial");
    level.setBlockAndUpdate(at, block("quark:crate").defaultBlockState());
    var crate = level.getBlockEntity(at);
    var delegate = crate.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
    delegate.insertItem(0, new ItemStack(Items.DIRT, 11), false);
    delegate.insertItem(1, new ItemStack(Items.COBBLESTONE, 13), false);
    var storage = (IItemHandler) inv.invoke(null,p,crate,null);
    require(storage.extractItem(0, 9, false).getCount() == 9 && delegate.getStackInSlot(0).getCount() == 2, "real crate capability withdrawal");
    storage.extractItem(0, 2, false);
    crate.getClass().getMethod("tick").invoke(crate);
    require(storage.extractItem(0, 13, false).getCount() == 13, "crate compaction moves stack to first slot");
    var boat = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("quark:quark_chest_boat")).create(level);
    var boatInventory = (IItemHandler) inv.invoke(null,p,boat,null);
    require(Sources.eligible(boat) && boatInventory.insertItem(0,new ItemStack(Items.CARROT,7),false).isEmpty()
        && boatInventory.extractItem(0,7,false).getCount() == 7, "Quark chest boat");
    for (String id : List.of("quark:lootr_spruce_chest", "quark:lootr_jungle_trapped_chest")) {
      level.removeBlock(at, false);
      level.setBlockAndUpdate(at, block(id).defaultBlockState());
      var loot = (RandomizableContainerBlockEntity) level.getBlockEntity(at);
      loot.setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"), 9876);
      var a = (IItemHandler) inv.invoke(null,p,loot,null);
      var b = (IItemHandler) inv.invoke(null, FakePlayerFactory.get(level,
          new com.mojang.authlib.GameProfile(UUID.randomUUID(), "QuarkOther")), loot,null);
      require(a != null && b != null, "Quark Lootr personal handlers " + id);
      int moved = 0, separate = 0;
      for (int i = 0; i < a.getSlots(); i++) {
        moved += a.extractItem(i,64,false).getCount();
        separate += b.getStackInSlot(i).getCount();
      }
      require(moved > 0 && separate > 0, "independent Lootr loot " + id);
    }
    LOG.info("QA128_QUARK_PASS double, trapped partial, crate compaction, boat, two personal Lootr variants");
    var village = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, level.getSharedSpawnPos(), 72, false);
    if (village == null) return;
    var replaced = false;
    var center = new net.minecraft.world.level.ChunkPos(village);
    for (int x = center.x - 4; x <= center.x + 4 && !replaced; x++)
      for (int z = center.z - 4; z <= center.z + 4 && !replaced; z++) {
        var chunk = level.getChunk(x, z);
        for (var pos : chunk.getBlockEntitiesPos()) {
          var actual = level.getBlockEntity(pos);
          if (!(actual instanceof ChestBlockEntity)) continue;
          var id = ForgeRegistries.BLOCKS.getKey(actual.getBlockState().getBlock());
          if (id != null && id.getNamespace().equals("quark")) {
            require(Sources.eligible(actual), "worldgen replacement is eligible");
            LOG.info("QA128_QUARK_WORLDGEN_PASS {}", id);
            replaced = true;
            break;
          }
        }
      }
  }

  private static Block block(String id) {
    var value = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
    require(value != null && value != Blocks.AIR, "missing block " + id);
    return value;
  }

  @SubscribeEvent
  public static void capabilities(net.minecraftforge.event.AttachCapabilitiesEvent<ItemStack> event) {
    if (!scenario.equals("ui") || !ModList.get().isLoaded("curios") || !event.getObject().is(Items.FEATHER)) return;
    var stack = event.getObject();
    event.addCapability(new ResourceLocation("rsqa124:capability_only"),
        top.theillusivec4.curios.api.CuriosApi.createCurioProvider(() -> stack));
  }

  private static void ui(ServerPlayer p) throws Exception {
    var stock = p.getPersistentData();
    stock.remove("ChestMagnetRecentPools");
    var old = new CompoundTag();
    old.put("Display", display("enigmaticlegacy:ocean_stone", 1));
    old.putLong("Time", System.currentTimeMillis() - 500);
    old.putLong("Count", 1);
    var rows = new ListTag();
    rows.add(old);
    stock.put("ChestMagnetRecentItems", rows);
    var magnet = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet")));
    p.getInventory().setItem(0, magnet);
    p.getInventory().selected = 0;
    var at = p.blockPosition().offset(1, 0, 0);
    p.serverLevel().setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
    var barrel = (BarrelBlockEntity) p.serverLevel().getBlockEntity(at);
    int slot = 0;
    for (var id : List.of("enigmaticlegacy:ocean_stone", "enigmaticaddons:revival_leaf",
        "minecraft:enchanted_book", "minecraft:paper", "minecraft:feather", "minecraft:stone")) {
      var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
      require(item != null && item != Items.AIR, id);
      barrel.setItem(slot++, new ItemStack(item));
    }
    var id = UUID.randomUUID();
    magnet.getOrCreateTagElement("ChestMagnet").putUUID("Id", id);
    // 开启菜单不消费物品；真实附近引擎在后续 tick 写入历史。
    net.minecraftforge.network.NetworkHooks.openScreen(p, new net.minecraft.world.SimpleMenuProvider(
        (n, inv, owner) -> new MagnetMenu(n, inv, false, id), net.minecraft.network.chat.Component.literal("宝箱磁铁")),
        buf -> { buf.writeBoolean(false); buf.writeUUID(id); });
    Net.sync(p, magnet, "QA128");
  }

  private static void compass(ServerPlayer p) throws Exception {
    var level = p.serverLevel();
    ticks++;
    if (stage == 0) {
      // 使用已自然生成的村庄；不伪造指南针回调或目标。
      anchor = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, p.blockPosition(), 64, false);
      require(anchor != null, "locate nearby natural village");
      // Edited 排除玩家近处的结构；从村庄四个区块外开始搜索。
      var pos = new BlockPos(anchor.getX() + (scenario.startsWith("edited") ? 64 : 0), level.getSeaLevel() + 3, anchor.getZ());
      p.teleportTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
      compass = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("explorerscompass:explorerscompass")));
      require(!compass.isEmpty(), "registered compass");
      p.getInventory().setItem(0, compass);
      p.getInventory().selected = 0;
      if (scenario.equals("enhance")) {
        var config = Class.forName("com.chaosthedude.explorerscompass.config.ConfigHandler").getField("GENERAL").get(null);
        var raw = config.getClass().getField("asyncStructureSearch").get(config);
        var toggle = (net.minecraftforge.common.ForgeConfigSpec.BooleanValue) raw;
        toggle.set(!Boolean.getBoolean("chestmagnet.qa128Sync"));
        LOG.info("QA128_ENHANCE_CONFIG asyncStructureSearch={}", toggle.get());
        if (Boolean.getBoolean("chestmagnet.qa128Enhance159")) {
          ((net.minecraftforge.common.ForgeConfigSpec.IntValue)
              config.getClass().getField("searchRequestCooldownMillis").get(config)).set(0);
          search(p, "STRUCTURE", VILLAGE);
          var first = requireSearching(p);
          search(p, "STRUCTURE", VILLAGE);
          require(!requireSearching(p).equals(first), "old manager stop must not cancel new revision");
          Class.forName("com.chaosthedude.explorerscompass.worker.SearchService")
              .getMethod("stopSearch", Player.class).invoke(null, p);
          require("CANCELLED".equals(CompassTargets.snapshot(p).getString("State")), "native manager stop must cancel current search");
          LOG.info("QA128_ENHANCE159_LIFECYCLE_PASS restart and native stop");
        }
      }
      search(p, "STRUCTURE", VILLAGE);
      require(!"FOUND".equals(CompassTargets.snapshot(p).getString("State")), "new search cleared previous marker");
      stage = 1;
      return;
    }
    if (ticks > 600) throw new IllegalStateException("compass timed out: " + CompassTargets.snapshot(p));
    var result = CompassTargets.snapshot(p);
    if (stage == 2) {
      if (p.getInventory().countItem(Items.NETHER_STAR) == 1) {
        require(((BarrelBlockEntity)level.getBlockEntity(container)).isEmpty(), "structure transferred real barrel");
        LOG.info("QA128_COMPASS_PASS {} exact start and structure receipt", scenario);
        if (scenario.equals("enhance")) {
          var target = Class.forName("com.chaosthedude.explorerscompass.util.SearchTarget");
          var structureKind = target.getField("STRUCTURE").get(null);
          var cls = compass.getItem().getClass();
          cls.getMethod("pointAt", ItemStack.class, target, ResourceLocation.class, int.class, int.class, int.class, ResourceLocation.class)
              .invoke(compass.getItem(), compass, structureKind, VILLAGE, 1234, 81, 5678, level.dimension().location());
          require(CompassTargets.snapshot(p).equals(result), "compass pointer cannot publish another structure");
          search(p, "BIOME", new ResourceLocation("minecraft:plains"));
          require(!"FOUND".equals(CompassTargets.snapshot(p).getString("State")), "valid biome search must revoke structure");
          stage = 3;
        } else {
          if (scenario.startsWith("edited")) {
            var previousPose = p.getPose();
            p.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            p.setShiftKeyDown(true);
            try { compass.getItem().use(level, p, net.minecraft.world.InteractionHand.MAIN_HAND); }
            finally { p.setShiftKeyDown(false); p.setPose(previousPose); }
            require("CANCELLED".equals(CompassTargets.snapshot(p).getString("State")), "native Edited cancel");
          }
          done = true;
        }
      }
      return;
    }
    if (stage == 3) {
      if (!result.getString("State").equals("NON_STRUCTURE")) return;
      search(p, "STRUCTURE", VILLAGE);
      compass.getItem().getClass().getMethod("cancelSearch", net.minecraft.world.level.Level.class, Player.class, ItemStack.class)
          .invoke(compass.getItem(), level, p, compass);
      require("CANCELLED".equals(CompassTargets.snapshot(p).getString("State")), "cancellation cannot revive previous target");
      LOG.info("QA128_ENHANCE_NEGATIVES_PASS pointer, biome and cancellation");
      done = true;
      return;
    }
    if (stage != 1 || !result.getString("State").equals("FOUND")) return;
    var chunk = level.getChunk(result.getInt("StartX"), result.getInt("StartZ"));
    Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        .get(new ResourceLocation(result.getString("Structure")));
    var start = chunk.getStartForStructure(structure);
    require(start != null && start.isValid() && start.getChunkPos().equals(chunk.getPos()), "exact confirmed start");
    container = start.getPieces().get(0).getBoundingBox().getCenter();
    level.setBlockAndUpdate(container, Blocks.BARREL.defaultBlockState());
    ((BarrelBlockEntity)level.getBlockEntity(container)).setItem(0, new ItemStack(Items.NETHER_STAR));
    p.teleportTo(container.getX() + .5, container.getY() + 2, container.getZ() + .5);
    magnet = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet")));
    p.getInventory().setItem(1, magnet);
    magnet.getOrCreateTagElement("ChestMagnet").putUUID("Id", UUID.randomUUID());
    var begin = method("StructureJobs", "beginAutomatic", ServerPlayer.class, ItemStack.class, CompoundTag.class);
    require((boolean)begin.invoke(null,p,magnet,result), "production structure task started");
    stage = 2;
  }

  @SuppressWarnings({"unchecked","rawtypes"})
  private static void search(ServerPlayer p, String type, ResourceLocation key) throws Exception {
    var item = compass.getItem();
    if (scenario.equals("enhance")) {
      var target = Class.forName("com.chaosthedude.explorerscompass.util.SearchTarget");
      var value = Enum.valueOf((Class)target, type);
      item.getClass().getMethod("searchForTargets", net.minecraft.world.level.Level.class, Player.class, target,
          List.class, BlockPos.class, ItemStack.class).invoke(item,p.serverLevel(),p,value,List.of(key),p.blockPosition(),compass);
    } else if (scenario.startsWith("edited")) {
      boolean fallback = scenario.equals("edited-fallback");
      var keys = fallback ? List.of(new ResourceLocation("minecraft:stronghold"), key) : List.of(key);
      item.getClass().getMethod("searchForStructure", net.minecraft.world.level.Level.class, Player.class,
          ResourceLocation.class, List.class, BlockPos.class, ItemStack.class)
          .invoke(item, p.serverLevel(), p, key, keys, p.blockPosition(), compass);
      if (fallback) failFirstWorker(p, item);
    } else {
      item.getClass().getMethod("searchForStructure", ServerLevel.class, Player.class, BlockPos.class,
          ResourceLocation.class, boolean.class, ItemStack.class)
          .invoke(item,p.serverLevel(),p,p.blockPosition(),key,false,compass);
    }
  }

  private static UUID requireSearching(ServerPlayer p) {
    var snapshot = CompassTargets.snapshot(p);
    require("SEARCHING".equals(snapshot.getString("State")), "expected active search: " + snapshot);
    return snapshot.getUUID("Revision");
  }

  private static void failFirstWorker(ServerPlayer p, Item item) throws Exception {
    var manager = CompassTargets.field(item, "workerManager");
    var workers = (List<?>)CompassTargets.field(manager, "workers");
    require(workers.size() == 2 && workers.get(0).getClass().getSimpleName().equals("ConcentricRingsSearchWorker"),
        "fixture must queue stronghold first and village second");
    var first = workers.get(0);
    var revision = requireSearching(p);
    // 用原生失败路径确定性地推进队列；第二个 worker 的搜索和收取由真实世界完成。
    var fail = first.getClass().getSuperclass().getDeclaredMethod("fail");
    fail.setAccessible(true);
    fail.invoke(first);
    require(workers.size() == 1 && workers.get(0) != first && requireSearching(p).equals(revision),
        "first worker's native failure must not terminate the whole search");
    LOG.info("QA128_EDITED_FALLBACK_PASS first failed, next started");
  }

  static void require(boolean check, String message) {
    if (!check) throw new IllegalStateException(message);
  }
}
