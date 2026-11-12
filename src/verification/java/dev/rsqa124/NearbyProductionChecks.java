package dev.rsqa124;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 隔离真实客户端使用，反射调用安装的生产 JAR；不替换磁铁调度器。 */
@Mod.EventBusSubscriber(modid = "rsqa124")
public final class NearbyProductionChecks {
  static final String SUITE = System.getProperty("chestmagnet.qa125", "");
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("nearby-qa125");
  static volatile boolean done;
  static volatile boolean running;
  static volatile String questFramePhase = "";
  private static ServerPlayer player;
  private static ItemStack magnet;
  private static Object engine, state;
  private static java.lang.reflect.Constructor<?> stateConstructor;
  private static Method work;
  private static final List<BarrelBlockEntity> boxes = new ArrayList<>();
  private static final List<Long> costs = new ArrayList<>();
  private static int batch, amount, first, service;
  private static long startTime, startTick, firstTime;
  private static final BlockPos ORIGIN = new BlockPos(8, 120, 8);

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent event) {
    if (SUITE.isEmpty() || done || event.phase != TickEvent.Phase.END
        || event.getServer().getPlayerList().getPlayers().isEmpty()) return;
    try {
      if ("chapel126".equals(SUITE)) {
        Production126Chapel.tick(event.getServer().getPlayerList().getPlayers().get(0));
        return;
      }
      if ("ftb126".equals(SUITE)) {
        var fixture = Class.forName("dev.rsqa124.Production126Quests");
        var tick = fixture.getDeclaredMethod("tick", ServerPlayer.class);
        tick.invoke(null, event.getServer().getPlayerList().getPlayers().get(0));
        return;
      }
      if ("rs125".equals(SUITE)) {
        Production125Rs.tick(event.getServer().getPlayerList().getPlayers().get(0));
        return;
      }
      if ("compat".equals(SUITE)) {
        Production125Compat.verify(event.getServer().getPlayerList().getPlayers().get(0));
        LOG.info("QA125_COMPAT_PASS");
        done = true;
        return;
      }
      if (player == null) setup(event.getServer().getPlayerList().getPlayers().get(0));
      var level = player.serverLevel();
      long tick = level.getGameTime();
      if (!running) {
        if (tick % 10 != (batch % 6 * 2 + 1) % 10) return;
        reset(level);
        startTick = tick;
        startTime = System.nanoTime();
        running = true;
      }
      long before = System.nanoTime();
      BenchCounters.start();
      try {
        work.invoke(engine, player, magnet, state, before + 2_000_000);
      } finally {
        BenchCounters.stop();
      }
      long after = System.nanoTime();
      costs.add(after - before);
      service++;
      int total = player.getInventory().countItem(Items.EMERALD);
      if (first == 0 && total > 0) {
        first = service;
        firstTime = after;
      }
      if (total < amount && tick - startTick < 120) return;
      if (total != amount || boxes.stream().anyMatch(b -> !b.isEmpty()))
        throw new IllegalStateException("bench count mismatch: " + total + "/" + amount);
      LOG.info("QA125_BENCH kind={} trial={} first_ticks={} last_ticks={} first_ms={} last_ms={}"
              + " items={} eligibility_checks={} probes={} costs_us={} overruns={}",
          batch < 6 ? "sparse" : "dense", batch % 6, first, service,
          (firstTime - startTime) / 1_000_000, (after - startTime) / 1_000_000, total,
          BenchCounters.candidates, BenchCounters.probes,
          costs.stream().map(n -> n / 1000).toList(),
          costs.stream().filter(n -> n > 2_000_000).count());
      running = false;
      if (++batch == 12) {
        LOG.info("QA125_BENCH_PASS");
        done = true;
      }
    } catch (Throwable e) {
      LOG.error("QA125_FAILED", e);
      done = true;
    }
  }

  private static void setup(ServerPlayer viewer) throws ReflectiveOperationException {
    var level = viewer.serverLevel();
    viewer.teleportTo(8.5, 123, 8.5);
    viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    for (int x = 0; x <= 1; x++)
      for (int z = 0; z <= 1; z++) level.getChunk(x, z);
    var profile = new GameProfile(UUID.fromString("f7e1b4d0-12c9-43ba-9011-000000000125"), "NearBench125");
    player = FakePlayerFactory.get(level, profile);
    player.setPos(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
    var type = Class.forName("dev.chestmagnet.Engine");
    var stateType = Class.forName("dev.chestmagnet.Engine$State");
    engine = type.getConstructor().newInstance();
    stateConstructor = stateType.getDeclaredConstructor();
    stateConstructor.setAccessible(true);
    work = type.getDeclaredMethod("work", ServerPlayer.class, ItemStack.class, stateType, long.class);
    work.setAccessible(true);
    var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
        new net.minecraft.resources.ResourceLocation("chestmagnet", "chest_magnet"));
    magnet = new ItemStack(Objects.requireNonNull(item));
  }

  private static void reset(ServerLevel level) throws ReflectiveOperationException {
    for (var box : boxes) level.setBlockAndUpdate(box.getBlockPos(), Blocks.AIR.defaultBlockState());
    boxes.clear();
    costs.clear();
    BenchCounters.candidates = BenchCounters.probes = 0;
    player.getInventory().clearContent();
    state = stateConstructor.newInstance();
    first = service = 0;
    int count = batch < 6 ? 1 : 24;
    int stacks = batch < 6 ? 1 : 8;
    for (int i = 0; i < count; i++) {
      var pos = new BlockPos(16 - i % 8, 120, 8 + i / 8);
      level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
      var box = (BarrelBlockEntity) level.getBlockEntity(pos);
      for (int s = 0; s < stacks; s++) box.setItem(s, new ItemStack(Items.EMERALD, 4));
      boxes.add(box);
    }
    amount = count * stacks * 4;
  }
}
