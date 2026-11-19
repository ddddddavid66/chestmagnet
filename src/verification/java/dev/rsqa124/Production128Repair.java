package dev.rsqa124;

import com.mojang.authlib.GameProfile;
import dev.chestmagnet.Destinations;
import dev.chestmagnet.Sources;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.data.DataStorage;
import noobanidus.mods.lootr.entity.LootrChestMinecartEntity;

/** 独立 QA 模组驱动正式 JAR，不打包进生产磁铁。 */
@Mod.EventBusSubscriber(modid = "rsqa124")
public final class Production128Repair {
  private static final String CASE = System.getProperty("chestmagnet.repair128", "");
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-repair-qa");
  private static final ResourceLocation MIRAGE_LOOT = new ResourceLocation("goetyawaken:chests/mirage");
  public static boolean done;
  private static int ticks;
  private static UUID playerId = UUID.fromString("a9ddca0e-e87f-4b03-8f22-64b1df8d4e69");

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent event) {
    if (done || CASE.isEmpty() || event.phase != TickEvent.Phase.END || ++ticks < 5) return;
    var server = event.getServer();
    try {
      switch (CASE) {
        case "lootr", "legacy" -> LootrCases.lootr(server.overworld(), LootrCases.freshLootrChest(server.overworld()));
        case "mirage" -> {
          if (!LootrCases.mirage(server.overworld())) return;
        }
        case "ancient", "ancient-baseline" -> {
          if (!AncientCityProbe.tick(server.overworld(), CASE.equals("ancient-baseline"), ticks)) return;
        }
        case "boss" -> BossProbe.run(server.overworld());
        case "aether-boss" -> { if(!AetherBossProbe.tick(server,ticks))return; }
        case "lootr-structure" -> { if(!LootrStructureProbe.tick(server.overworld(),ticks))return; }
        case "structure-protection" -> { if(!StructureProtectionProbe.tick(server.overworld(),ticks))return; }
        case "crypt" -> { if(!NaturalCryptProbe.tick(server.overworld(),ticks))return; }
        case "crypt-reopen" -> NaturalCryptProbe.reopen(server.overworld());
        case "buried-original" -> { if(!OriginalBuriedProbe.tick(server.overworld(),ticks))return; }
        case "buried-reopen" -> BuriedProbe.reopen(server.overworld());
        case "buried", "buried-baseline", "buried-off" -> {
          if (!BuriedProbe.tick(server.overworld(), CASE, ticks)) return;
        }
        case "pets", "pets-baseline" -> pets(server.getLevel(Level.END));
        case "pets-reopen" -> petsReopen(server.getLevel(Level.END));
        case "repair-screen" -> Repair128Screening.run(server.overworld());
        default -> throw new IllegalArgumentException(CASE);
      }
      LOG.info("REPAIR128_PASS {}", CASE);
    } catch (Throwable ex) {
      LOG.error("REPAIR128_FAIL {}", CASE, ex);
    }
    done = true;
    server.halt(false);
  }

  static ServerPlayer player(ServerLevel level, String name) {
    return FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), name));
  }

  static ItemStack magnet() {
    return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet")));
  }

  static void require(boolean value, String what) {
    if (!value) throw new IllegalStateException(what);
  }

  private static int count(IItemHandler inv) {
    int total = 0;
    for (int slot = 0; slot < inv.getSlots(); slot++) total += inv.getStackInSlot(slot).getCount();
    return total;
  }

  private static final class LootrCases {
    private static BlockPos mirageChest;

    private static void lock(RandomizableContainerBlockEntity chest) {
      var nbt = chest.saveWithoutMetadata();
      nbt.putString("Lock", "Mirage QA Key");
      chest.load(nbt);
      chest.setChanged();
    }

    private static RandomizableContainerBlockEntity freshLootrChest(ServerLevel level) {
      var at = level.getSharedSpawnPos().above(5);
      var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("lootr:lootr_chest"));
      level.setBlockAndUpdate(at, block.defaultBlockState());
      var chest = (RandomizableContainerBlockEntity) level.getBlockEntity(at);
      chest.setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"), 42);
      lock(chest);
      return chest;
    }

    private static void lootr(ServerLevel level, RandomizableContainerBlockEntity chest) {
      var p = player(level, "LootrQA1");
      var q = player(level, "LootrQA2");
      var m = magnet();
      m.getOrCreateTagElement("ChestMagnet").putBoolean("CollectLocked", false);
      require(Sources.inventory(p, chest, m) == null, "option off must respect lock");
      p.getInventory().setItem(0, new ItemStack(Items.TRIPWIRE_HOOK).setHoverName(Component.literal("Mirage QA Key")));
      require(Sources.inventory(p, chest, m) != null, "native key works with option off");
      p.getInventory().setItem(0, ItemStack.EMPTY);
      m.getOrCreateTagElement("ChestMagnet").putBoolean("CollectLocked", true);
      var id = ((ILootBlockEntity) chest).getTileId();
      boolean oldDecay = ConfigManager.DECAY_ALL.get();
      ConfigManager.DECAY_ALL.set(true);
      try {
        var a = Sources.inventory(p, chest, m);
        var b = Sources.inventory(q, chest, m);
        require(a != null && b != null && count(a) > 0 && count(b) > 0, "real personal loot");
        int before = count(b);
        for (int slot = 0; slot < a.getSlots(); slot++) a.extractItem(slot, 64, false);
        require(count(Sources.inventory(p, chest, m)) == 0 && count(b) == before, "personal loot isolation and no reroll");
        require(p.containerMenu == p.inventoryMenu && q.containerMenu == q.inventoryMenu, "no menus");
        require(DataStorage.getDecayValue(id) == -1, "magnet did not start decay");
        require("Mirage QA Key".equals(chest.saveWithoutMetadata().getString("Lock")), "shared Lock preserved");
        DataStorage.setDecaying(id, 0);
        require(DataStorage.isDecayed(id) && Sources.inventory(p, chest, m) == null
            && level.getBlockEntity(chest.getBlockPos()) == chest, "expired chest preserved");
        var cart = (LootrChestMinecartEntity) ForgeRegistries.ENTITY_TYPES
            .getValue(new ResourceLocation("lootr:lootr_minecart")).create(level);
        var at = chest.getBlockPos();
        cart.setPos(at.getX() + 0.5, at.getY() + 2, at.getZ() + 0.5);
        cart.setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"), 42);
        require(level.addFreshEntity(cart), "spawn live Lootr cart");
        require(count(Sources.inventory(p, cart, m)) > 0 && DataStorage.getDecayValue(cart.getUUID()) == -1, "personal cart loot and no decay");
        DataStorage.setDecaying(cart.getUUID(), 0);
        require(Sources.inventory(p, cart, m) == null && cart.isAlive(), "expired cart preserved");
        LOG.info("REPAIR128_LOOTR_LOCK_ISOLATION_PASS second={} blockId={}", before, id);
      } finally {
        ConfigManager.DECAY_ALL.set(oldDecay);
      }
    }

    private static boolean mirage(ServerLevel level) throws Exception {
      if (mirageChest == null) {
        mirageChest = locateChest(level, "goetyawaken:mirage", MIRAGE_LOOT, null);
        var raw = (RandomizableContainerBlockEntity) level.getBlockEntity(mirageChest);
        lock(raw); // 真实结构的 LootTable；原版 Lock 是测试额外加的。
        var p = player(level, "BeforeConversion");
        require(Sources.inventory(p, raw, magnet()) == null, "must not steal the unopened loot table");
        require(MIRAGE_LOOT.toString().equals(raw.saveWithoutMetadata().getString("LootTable")), "unopened loot table survives");
      structureRecheck(level, mirageChest, false);
        var cp = new ChunkPos(mirageChest);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
          level.setChunkForced(cp.x + dx, cp.z + dz, true); // QA only: 满足 Lootr 原生转换条件。
        LOG.info("REPAIR128_MIRAGE_VANILLA_WAIT {} at {}", raw.getBlockState(), mirageChest);
        return false;
      }
      if (!(level.getBlockEntity(mirageChest) instanceof ILootBlockEntity)) {
        require(ticks < 800, "Lootr did not convert generated Mirage chest");
        return false;
      }
      structureRecheck(level, mirageChest, true);
      lootr(level, (RandomizableContainerBlockEntity)level.getBlockEntity(mirageChest));
      return true;
    }
  }

  @SuppressWarnings("unchecked")
  private static void structureRecheck(ServerLevel level, BlockPos at, boolean converted) throws Exception {
    var p = player(level, "MirageStructure");
    p.setPos(at.getX() + 1, at.getY(), at.getZ());
    var m = magnet();
    p.getInventory().setItem(0, m);
    var magnetId = UUID.randomUUID();
    m.getOrCreateTagElement("ChestMagnet").putUUID("Id", magnetId);
    var jobType = Class.forName("dev.chestmagnet.StructureJobs$Job");
    var constructor = jobType.getDeclaredConstructor();
    constructor.setAccessible(true);
    var job = constructor.newInstance();
    var fields = new java.util.HashMap<String, java.lang.reflect.Field>();
    for (var field : jobType.getDeclaredFields()) {
      field.setAccessible(true);
      fields.put(field.getName(), field);
    }
    fields.get("level").set(job, level);
    fields.get("magnet").set(job, magnetId);
    fields.get("viewing").setBoolean(job, false);
    var region = new net.minecraft.world.level.levelgen.structure.BoundingBox(at);
    fields.get("region").set(job, region);
    fields.get("body").set(job, region);
    fields.get("start").set(job, new ChunkPos(at));
    var result = new net.minecraft.nbt.CompoundTag();
    result.putString("Structure", "goetyawaken:mirage");
    fields.get("result").set(job, result);
    ((java.util.Set<BlockPos>) fields.get("deferredLootr").get(job)).add(at);
    var collect = Class.forName("dev.chestmagnet.StructureJobs")
        .getDeclaredMethod("collect", ServerPlayer.class, jobType, long.class);
    collect.setAccessible(true);
    for (int step = 0; step < 20 && !fields.get("complete").getBoolean(job); step++)
      collect.invoke(null, p, job, System.nanoTime() + 10_000_000L);
    int processed = fields.get("processed").getInt(job);
    require(fields.get("complete").getBoolean(job) && fields.get("loadRequests").getInt(job) == 0
        && ((java.util.Map<?, ?>) fields.get("tickets").get(job)).isEmpty(), "recheck does not ask for tickets");
    require(converted ? processed > 0 && fields.get("lootrWaiting").getInt(job) == 0
        : processed == 0 && fields.get("lootrWaiting").getInt(job) == 1,
        "loaded recheck collects converted sources; otherwise reports incomplete");
    LOG.info("REPAIR128_STRUCTURE_RECHECK_PASS converted={} processed={} request=0", converted, processed);
  }

  private static BlockPos locateChest(ServerLevel level, String structureId, ResourceLocation table, String name) {
    return locateChest(level, structureId, table, name, null);
  }

  static BlockPos locateChest(ServerLevel level, String structureId, ResourceLocation table, String name,
                              BlockPos excludeAt) {
    return locateChest(level, structureId, table, name, excludeAt, new BlockPos(650,75,650));
  }

  static BlockPos locateChest(ServerLevel level, String structureId, ResourceLocation table, String name, BlockPos excludeAt, BlockPos origin) {
    var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
    var structure = registry.get(new ResourceLocation(structureId));
    require(structure != null, "Unknown structure: " + structureId);
    var chunks = level.getChunkSource();
    var nearest = chunks.getGenerator().findNearestMapStructure(level,
        HolderSet.direct(registry.wrapAsHolder(structure)), origin, 128, false);
    require(nearest != null, "Cannot find real structure " + structureId);
    var pos = nearest.getFirst();
    var start = level.getChunk(pos).getStartForStructure(structure);
    if (start == null || !start.isValid()) {
      var cp = new ChunkPos(pos);
      for (int dx = -2; dx <= 2 && (start == null || !start.isValid()); dx++)
        for (int dz = -2; dz <= 2 && (start == null || !start.isValid()); dz++)
          start = level.getChunk(cp.x + dx, cp.z + dz).getStartForStructure(structure);
    }
    require(start != null && start.isValid(), "Missing structure start " + pos);
    for (var piece : start.getPieces()) {
      var box = piece.getBoundingBox();
      for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
        for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
          var chunk = level.getChunk(x, z);
          for (var at : chunk.getBlockEntitiesPos()) {
            if (at.equals(excludeAt) || !box.isInside(at)
                || !(chunk.getBlockEntity(at) instanceof RandomizableContainerBlockEntity c))
              continue;
            if (table != null && c instanceof ChestBlockEntity && c.getBlockState().is(Blocks.CHEST)
                && table.toString().equals(c.saveWithoutMetadata().getString("LootTable"))) return at;
            if(name != null && name.startsWith("block:") && ForgeRegistries.BLOCKS.getKey(c.getBlockState().getBlock()).toString().equals(name.substring(6))) return at;
            if (name != null && c.getCustomName() != null && name.equals(c.getCustomName().getString())) return at;
          }
        }
    }
    throw new IllegalStateException("No expected chest " + structureId + " near " + pos);
  }

  private static void pets(ServerLevel level) throws Exception {
    var at = locateChest(level, "inventorypets:code_structure_space_dungeon", null, "Space Dungeon");
    var chest = (ChestBlockEntity) level.getBlockEntity(at);
    var p = FakePlayerFactory.get(level, new GameProfile(playerId, "DungeonQA"));
    var token = chest.getItem(4);
    require(token.is(Items.POISONOUS_POTATO) && token.getHoverName().getString().equals("Rebecca"), "real generated sentinel");
    var m = magnet();
    var inv = Sources.inventory(p, chest, m);
    require(inv != null, "Space Dungeon is ready for collection");
    if (CASE.equals("pets-baseline")) {
      require(inv.extractItem(4, 64, false).is(Items.POISONOUS_POTATO) && chest.isEmpty(),
          "1.2.8 incorrectly removes the trigger and leaves the chest empty");
      require("Space Dungeon".equals(chest.getCustomName().getString()), "1.2.8 did not run native initialization");
      LOG.info("REPAIR128_PETS_BASELINE_REPRO {}", at);
      return;
    }
    int generated = count(inv);
    require(generated > 1 && !token.isEmpty() && !"Space Dungeon".equals(chest.getName().getString()),
        "native generation replaced sentinel and cleared trigger name");
    var target = Destinations.get(p, 0);
    var move = Class.forName("dev.chestmagnet.Transfer").getDeclaredMethod("move",
        IItemHandler.class, int.class, int.class, Destinations.Target.class, ItemStack.class);
    move.setAccessible(true);
    for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.BEDROCK, 64));
    int first = 0;
    while (inv.getStackInSlot(first).isEmpty()) first++;
    var sample = inv.getStackInSlot(first);
    require(target.insert(sample, true).getCount() == sample.getCount()
        && count(Sources.inventory(p, chest, m)) == generated, "full target preserves generated inventory");
    p.getInventory().setItem(0, ItemStack.EMPTY);
    int received = (int)move.invoke(null, inv, first, 1, target, m);
    require(received == 1 && p.getInventory().getItem(0).getCount() == 1
        && count(Sources.inventory(p, chest, m)) == generated - received, "partial collection and no reroll");
    for (int i = 1; i < 36; i++) p.getInventory().setItem(i, ItemStack.EMPTY);
    for (int slot = 0; slot < inv.getSlots(); slot++) received += (int)move.invoke(null, inv, slot, 64, target, m);
    require(received == generated && p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum() == generated
        && count(Sources.inventory(p, chest, m)) == 0, "real destination and source counts reconcile");
    var ordinary = at.above(2);
    level.setBlockAndUpdate(ordinary, Blocks.CHEST.defaultBlockState());
    var normal = (ChestBlockEntity)level.getBlockEntity(ordinary);
    normal.setItem(4, new ItemStack(Items.POISONOUS_POTATO));
    require(Sources.inventory(p, normal, magnet()).extractItem(4, 1, false).is(Items.POISONOUS_POTATO),
        "unrelated chest retains normal behavior");
    java.nio.file.Files.writeString(java.nio.file.Path.of("pets-reopen-pos.txt"), Long.toString(at.asLong()));
    LOG.info("REPAIR128_PETS_NATIVE_PASS {} generated={} received={}", at, generated, received);
  }

  private static void petsReopen(ServerLevel level) throws Exception {
    var at = BlockPos.of(Long.parseLong(java.nio.file.Files.readString(java.nio.file.Path.of("pets-reopen-pos.txt")).trim()));
    var chest = level.getChunkAt(at).getBlockEntity(at);
    require(chest instanceof ChestBlockEntity container && !"Space Dungeon".equals(container.getName().getString()),
        "the originally generated chest persisted with trigger removed");
    var p = FakePlayerFactory.get(level, new GameProfile(playerId, "DungeonQA"));
    require(count(Sources.inventory(p, chest, magnet())) == 0, "server restart does not reroll loot");
    LOG.info("REPAIR128_PETS_RESTART_PASS {}", at);
  }
}
