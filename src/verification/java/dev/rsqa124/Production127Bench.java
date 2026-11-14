package dev.rsqa124;

import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** 隔离服务端热区块试验；单轮在主线程执行，不用数值冒充实际 tick 或冷加载性能。 */
final class Production127Bench {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-qa127");
  private static final String FLAG = "chestmagnet.bench127";

  static void run(ServerLevel level, ServerPlayer p, ItemStack magnet) throws ReflectiveOperationException {
    run(level, p, magnet, false);
  }

  static void screen(ServerLevel level, ServerPlayer p, ItemStack magnet) throws ReflectiveOperationException {
    run(level, p, magnet, true);
  }

  private static void run(ServerLevel level, ServerPlayer p, ItemStack magnet, boolean screening) throws ReflectiveOperationException {
    var engineType = Class.forName("dev.chestmagnet.Engine");
    var engine = engineType.getConstructor().newInstance();
    var stateType = Class.forName("dev.chestmagnet.Engine$State");
    var stateCtor = stateType.getDeclaredConstructor();
    stateCtor.setAccessible(true);
    var work = engineType.getDeclaredMethod("work", ServerPlayer.class, ItemStack.class, stateType, long.class);
    work.setAccessible(true);
    var jobType = Class.forName("dev.chestmagnet.StructureJobs$Job");
    var jobCtor = jobType.getDeclaredConstructor();
    jobCtor.setAccessible(true);
    var collect = Class.forName("dev.chestmagnet.StructureJobs")
        .getDeclaredMethod("collect", ServerPlayer.class, jobType, long.class);
    collect.setAccessible(true);
    var fields = new HashMap<String, java.lang.reflect.Field>();
    for (var f : jobType.getDeclaredFields()) { f.setAccessible(true); fields.put(f.getName(), f); }
    var at = new BlockPos(6, 110, 6);
    level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
    var barrel = (BarrelBlockEntity) level.getBlockEntity(at);
    var cp = new ChunkPos(at);
    var region = new BoundingBox(6, -64, 6, 6, 319, 6);
    var tag = magnet.getOrCreateTagElement("ChestMagnet");
    tag.putInt("X", 32);
    tag.putInt("Z", 32);
    for (int pair = -1; pair < (screening ? 5 : 3); pair++) {
      for (int run = 0; run < 2; run++) {
        boolean candidate = ((pair + run) & 1) == 1;
        System.setProperty(FLAG, Boolean.toString(candidate));
        System.setProperty("chestmagnet.bench128EmptySlots", Boolean.toString(candidate));
        for (String kind : List.of("near-static", "near-moving", "structure-hot")) {
          var costs = new long[40];
          int received = 0;
          for (int i = 0; i < costs.length; i++) {
            p.getInventory().clearContent();
            p.getInventory().setItem(0, magnet);
            p.setPos(6.5, 110, 7.5);
            barrel.setItem(26, new ItemStack(Items.EMERALD, 3));
            if (kind.startsWith("near")) {
              // 清理前一项烟测来源，避免其空槽占用本次单轮槽位额度。
              level.removeBlock(new BlockPos(7, 110, 7), false);
              level.removeBlock(new BlockPos(8, 110, 8), false);
              var state = stateCtor.newInstance();
              if (kind.equals("near-moving")) {
                barrel.setItem(26, ItemStack.EMPTY);
                work.invoke(engine, p, magnet, state, Long.MAX_VALUE);
                p.setPos(9.5, 110, 7.5);
                state = stateCtor.newInstance(); // 先测移动后第一次启动的共同排序开销。
                barrel.setItem(26, new ItemStack(Items.EMERALD, 3));
              }
              costs[i] = measure(work, engine, p, magnet, state, Long.MAX_VALUE);
            } else {
              var job = jobCtor.newInstance();
              fields.get("level").set(job, level);
              fields.get("dimension").set(job, level.dimension().location().toString());
              fields.get("start").set(job, cp);
              fields.get("magnet").set(job, tag.getUUID("Id"));
              var result = new CompoundTag();
              result.putString("Structure", "chestmagnet:qa127_bench");
              fields.get("result").set(job, result);
              fields.get("running").setBoolean(job, true);
              fields.get("region").set(job, region);
              fields.get("body").set(job, region);
              fields.get("total").setInt(job, 1);
              ((Collection<ChunkPos>) fields.get("plan").get(job)).add(cp);
              ((Collection<ChunkPos>) fields.get("waiting").get(job)).add(cp);
              costs[i] = measure(collect, null, p, job, Long.MAX_VALUE);
              if (!fields.get("complete").getBoolean(job)) throw new IllegalStateException("hot task unfinished");
            }
            received += p.getInventory().countItem(Items.EMERALD);
            if (!barrel.isEmpty()) throw new IllegalStateException("bench missed source " + kind + " at trial " + i);
          }
          if (received != costs.length * 3) throw new IllegalStateException("wrong bench total");
          Arrays.sort(costs);
          LOG.info("QA127_BENCH pair={} candidate={} kind={} p50_us={} p95_us={} items={}",
              pair, candidate, kind, costs[19] / 1000, costs[37] / 1000, received);
          if (screening) LOG.info("QA128_SCREEN pair={} candidate={} kind={} p50_us={} p95_us={} items={}",
              pair, candidate, kind, costs[19] / 1000, costs[37] / 1000, received);
        }
      }
    }
    System.clearProperty(FLAG);
    System.clearProperty("chestmagnet.bench128EmptySlots");
  }

  private static long measure(Method method, Object owner, Object... params) throws ReflectiveOperationException {
    long now = System.nanoTime();
    method.invoke(owner, params);
    return System.nanoTime() - now;
  }
}
