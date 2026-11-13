package dev.rsqa124;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 正式 JAR 无可选模组烟测：真实服务端，独立辅助模组。 */
@Mod.EventBusSubscriber(modid = "rsqa124")
public final class Production126Dedicated {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-qa126");
  private static boolean checked;

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent e) {
    if (checked || e.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.qa126Server")) return;
    checked = true;
    try {
      var server = e.getServer();
      var level = server.overworld();
      var at = new BlockPos(7, 110, 7);
      level.removeBlock(new BlockPos(6, 110, 6), false);
      level.removeBlock(new BlockPos(8, 110, 8), false);
      var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CM126Minimal"));
      player.setPos(at.getX() + 0.5, at.getY(), at.getZ() - 0.5);
      var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet", "chest_magnet"));
      var magnet = new ItemStack(item);
      player.getInventory().setItem(0, magnet);
      level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
      var barrel = (BarrelBlockEntity) level.getBlockEntity(at);
      barrel.setItem(0, new ItemStack(Items.DIAMOND, 11));
      var engineType = Class.forName("dev.chestmagnet.Engine");
      var stateType = Class.forName("dev.chestmagnet.Engine$State");
      var ctor = stateType.getDeclaredConstructor();
      ctor.setAccessible(true);
      var work = engineType.getDeclaredMethod("work", ServerPlayer.class, ItemStack.class, stateType, long.class);
      work.setAccessible(true);
      var engine = engineType.getDeclaredConstructor().newInstance();
      var state = ctor.newInstance();
      magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 3);
      work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
      require(barrel.getItem(0).getCount() == 11, "optional mode never extracts without its mod");
      magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 0);
      for (int i = 0; i < 4 && !barrel.isEmpty(); i++) work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
      require(barrel.isEmpty() && player.getInventory().countItem(Items.DIAMOND) == 11,
          "minimal nearby collection uses normal inventory");
      var history = player.getPersistentData().getList("ChestMagnetRecentItems", 10);
      if (Boolean.getBoolean("chestmagnet.qa127Server")) {
        require(history.size() == 1 && history.getCompound(0).getLong("Count") == 11,
            "only confirmed insert recorded");
        checkStructure(level, player, magnet);
        if (Boolean.getBoolean("chestmagnet.bench127Suite")) Production127Bench.run(level, player, magnet);
        LOG.info("QA127_DEDICATED_PASS vanilla nearby + structure receipts on release JAR");
      }
      if (net.minecraftforge.fml.ModList.get().isLoaded("beyonddimensions")) {
        var cls = Class.forName("com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet");
        var network = cls.getMethod("createNewNetForPlayer", net.minecraft.world.entity.player.Player.class,
            long.class, int.class).invoke(null, player, 5L, 1);
        require(network != null, "native network");
        barrel.setItem(0, new ItemStack(Items.EMERALD, 8));
        magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 3);
        for (int i = 0; i < 3; i++) work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
        var storage = cls.getMethod("getUnifiedStorage").invoke(network);
        var keyInterface = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey");
        var keyType = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey");
        var key = keyType.getConstructor(ItemStack.class).newInstance(new ItemStack(Items.EMERALD));
        var entry = storage.getClass().getMethod("getStackByKey", keyInterface).invoke(storage, key);
        long amount = (long) entry.getClass().getMethod("amount").invoke(entry);
        require(barrel.getItem(0).getCount() == 3
            && amount == 5, "native partial insertion retains three in source");
        LOG.info("QA126_FLOOR_STORAGE_PASS 5 stored, 3 untouched");
      } else {
        LOG.info("QA126_MINIMAL_PASS official server: unavailable optional mode preserved source, normal mode moved 11");
      }
    } catch (Throwable error) {
      LOG.error("QA126_MINIMAL_FAILED", error);
    } finally {
      e.getServer().halt(false);
    }
  }

  private static void checkStructure(net.minecraft.server.level.ServerLevel level, ServerPlayer player,
      ItemStack magnet) throws ReflectiveOperationException {
    var type = Class.forName("dev.chestmagnet.StructureJobs");
    var jobType = Class.forName("dev.chestmagnet.StructureJobs$Job");
    var ctor = jobType.getDeclaredConstructor();
    ctor.setAccessible(true);
    var job = ctor.newInstance();
    var at = new BlockPos(8, 110, 8);
    var cp = new net.minecraft.world.level.ChunkPos(at);
    require(level.areEntitiesLoaded(cp.toLong()), "fixture spawn chunk ready");
    level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
    var barrel = (BarrelBlockEntity) level.getBlockEntity(at);
    barrel.setItem(26, new ItemStack(Items.GOLD_INGOT, 3));
    var region = new net.minecraft.world.level.levelgen.structure.BoundingBox(8, -64, 8, 8, 319, 8);
    put(job, "level", level);
    put(job, "dimension", level.dimension().location().toString());
    put(job, "start", cp);
    put(job, "magnet", magnet.getOrCreateTagElement("ChestMagnet").getUUID("Id"));
    var result = new net.minecraft.nbt.CompoundTag();
    result.putString("Structure", "chestmagnet:qa127");
    put(job, "result", result);
    put(job, "running", true);
    put(job, "region", region);
    put(job, "body", region);
    put(job, "total", 1);
    ((java.util.Collection<Object>) field(job, "plan").get(job)).add(cp);
    ((java.util.Collection<Object>) field(job, "waiting").get(job)).add(cp);
    var collect = type.getDeclaredMethod("collect", ServerPlayer.class, jobType, long.class);
    collect.setAccessible(true);
    collect.invoke(null, player, job, Long.MAX_VALUE);
    var records = player.getPersistentData().getList("ChestMagnetRecentItems", 10);
    require(barrel.isEmpty() && records.size() == 2 && records.getCompound(0).getLong("Count") == 3
        && field(job, "complete").getBoolean(job), "structure receipt logged once after complete");
  }

  private static java.lang.reflect.Field field(Object object, String name) throws ReflectiveOperationException {
    var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void put(Object object, String name, Object value) throws ReflectiveOperationException {
    field(object, name).set(object, value);
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private Production126Dedicated() {}
}
