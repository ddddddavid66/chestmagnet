package dev.rsqa124;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** 只测独立热点原型，不替代正式双 JAR 的游戏 A/B，也不进入生产构建。 */
final class Repair128Screening {
  private static volatile long sink;

  static void run(ServerLevel level) {
    var origin = new ChunkPos(level.getSharedSpawnPos());
    var at = new BlockPos(origin.getMinBlockX() + 4, 115, origin.getMinBlockZ() + 6);
    var chunk = level.getChunkAt(at);
    int section = chunk.getSectionIndex(at.getY());
    var state = chunk.getSection(section);
    screen("section-near-sparse", () -> sparseSection(state, at, false), () -> sparseSection(state, at, true));

    // 使用真实分节读取路径；钻石块仅作命中哨兵，不涉及正式识别的特殊来源。
    level.setBlockAndUpdate(at, Blocks.DIAMOND_BLOCK.defaultBlockState());
    screen("section-hit", () -> denseSection(state, at, false), () -> denseSection(state, at, true));
    level.removeBlock(at, false);

    var footprint = new ArrayList<BoundingBox>();
    int[] masks = new int[16];
    var positions = new BlockPos[128];
    var random = new Random(81933858);
    for (int i = 0; i < 96; i++) {
      int x = random.nextInt(16), z = random.nextInt(16);
      int width = Math.min(3, 15 - x), depth = Math.min(3, 15 - z);
      footprint.add(new BoundingBox(origin.getMinBlockX() + x, level.getMinBuildHeight(), origin.getMinBlockZ() + z,
          origin.getMinBlockX() + x + width, level.getMaxBuildHeight() - 1, origin.getMinBlockZ() + z + depth));
      for (int row = z; row <= z + depth; row++) masks[row] |= ((1 << (width + 1)) - 1) << x;
    }
    for (int i = 0; i < positions.length; i++)
      positions[i] = new BlockPos(origin.getMinBlockX() + random.nextInt(16), random.nextInt(370) - 60,
          origin.getMinBlockZ() + random.nextInt(16));
    for (var pos : positions)
      Production128.require(inUnion(footprint, pos) == inMask(masks, pos), "exact footprint");
    screen("footprint-128-points", () -> footprint(footprint, masks, positions, false),
        () -> footprint(footprint, masks, positions, true));

    var queue = new ArrayList<ChunkPos>();
    for (int x = -7; x <= 7; x++)
      for (int z = -7; z <= 7; z++) queue.add(new ChunkPos(origin.x + x, origin.z + z));
    queue.sort(java.util.Comparator.comparingLong(c -> distance(c, origin)));
    int[] lookups = {0};
    var a = closest(level, queue, origin, false, lookups);
    int baselineLookups = lookups[0];
    lookups[0] = 0;
    var b = closest(level, queue, origin, true, lookups);
    Production128.require(a != null && a.equals(b), "ready selection tie/order preserved");
    Production128.LOG.info("REPAIR128_SCREEN_READY probes={} vs {}", baselineLookups, lookups[0]);
    screen("ready-225-chunks", () -> closest(level, queue, origin, false, null).toLong(),
        () -> closest(level, queue, origin, true, null).toLong());
  }

  private static long sparseSection(LevelChunkSection state, BlockPos at, boolean cached) {
    if (!state.getStates().maybeHas(s -> s.is(Blocks.DIAMOND_BLOCK))) return 0;
    return denseSection(state, at, cached);
  }

  private static long denseSection(LevelChunkSection state, BlockPos at, boolean cached) {
    int x0 = Math.max(at.getX() - 8, at.getX() & ~15);
    int z0 = Math.max(at.getZ() - 8, at.getZ() & ~15);
    int y0 = Math.max(at.getY() - 3, at.getY() & ~15);
    long result = 0;
    if (cached) {
      int width = Math.min(at.getX() + 8, (at.getX() & ~15) + 15) - x0 + 1;
      int depth = Math.min(at.getZ() + 8, (at.getZ() & ~15) + 15) - z0 + 1;
      int height = Math.min(at.getY() + 3, (at.getY() & ~15) + 15) - y0 + 1;
      int visits = Math.min(512, width * depth * height);
      for (int i = 0, x = 0, y = 0, z = 0; i < visits; i++) {
        if (state.getBlockState((x0 + x) & 15, (y0 + y) & 15, (z0 + z) & 15).is(Blocks.DIAMOND_BLOCK)) result += i + 1;
        if (++x == width) { x = 0; if (++z == depth) { z = 0; y++; } }
      }
      return result;
    }
    for (int i = 0; i < 512; i++) {
      int width = Math.min(at.getX() + 8, (at.getX() & ~15) + 15) - x0 + 1;
      int depth = Math.min(at.getZ() + 8, (at.getZ() & ~15) + 15) - z0 + 1;
      int height = Math.min(at.getY() + 3, (at.getY() & ~15) + 15) - y0 + 1;
      if (i >= width * depth * height) break;
      int x = x0 + i % width, z = z0 + i / width % depth, y = y0 + i / (width * depth);
      if (state.getBlockState(x & 15, y & 15, z & 15).is(Blocks.DIAMOND_BLOCK)) result += i + 1;
    }
    return result;
  }

  private static boolean inUnion(List<BoundingBox> footprint, BlockPos pos) {
    for (var box : footprint) if (box.isInside(pos)) return true;
    return false;
  }

  private static boolean inMask(int[] masks, BlockPos pos) {
    return (masks[pos.getZ() & 15] & (1 << (pos.getX() & 15))) != 0;
  }

  private static long footprint(List<BoundingBox> boxes, int[] masks, BlockPos[] positions, boolean cached) {
    long result = 0;
    for (var p : positions) if (cached ? inMask(masks, p) : inUnion(boxes, p)) result += p.asLong();
    return result;
  }

  private static ChunkPos closest(ServerLevel level, List<ChunkPos> queue, ChunkPos origin, boolean gate, int[] counts) {
    long nearest = Long.MAX_VALUE;
    ChunkPos best = null;
    for (var pos : queue) {
      long distance = distance(pos, origin);
      if (gate && distance >= nearest) continue;
      if (counts != null) counts[0]++;
      var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
      if (chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)
          && level.areEntitiesLoaded(pos.toLong()) && distance < nearest) {
        nearest = distance;
        best = pos;
      }
    }
    return best;
  }

  private static long distance(ChunkPos first, ChunkPos second) {
    long x = (long)first.x - second.x, z = (long)first.z - second.z;
    return x * x + z * z;
  }

  private static void screen(String name, LongSupplier baseline, LongSupplier candidate) {
    Production128.require(baseline.getAsLong() == candidate.getAsLong(), "screening result equality: " + name);
    for (int i = 0; i < 400; i++) sink ^= baseline.getAsLong() ^ candidate.getAsLong();
    long[][] a = new long[5][128], b = new long[5][128];
    for (int pair = 0; pair < 5; pair++) {
      for (int s = 0; s < 128; s++) {
        if ((pair & 1) == 0) { a[pair][s] = time(baseline); b[pair][s] = time(candidate); }
        else { b[pair][s] = time(candidate); a[pair][s] = time(baseline); }
      }
      Arrays.sort(a[pair]); Arrays.sort(b[pair]);
      Production128.LOG.info("REPAIR128_SCREEN {} pair={} baseline_p95_ns={} trial_p95_ns={}",
          name, pair + 1, a[pair][121], b[pair][121]);
    }
  }

  private static long time(LongSupplier logic) {
    long now = System.nanoTime();
    sink ^= logic.getAsLong();
    return System.nanoTime() - now;
  }
}
