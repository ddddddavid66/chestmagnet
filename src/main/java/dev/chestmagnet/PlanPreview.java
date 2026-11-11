package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** 只读已加载区块的二维楼层快照。采样进度不参与任何收取完成判定。 */
public final class PlanPreview {
  static final int SIZE = 128, DEPTH = 8, MAX_READS = 262144;
  static final int UNKNOWN = 0, WALL = 1, FLOOR = 2, OPEN = 3, LIQUID = 4;
  private static final int PACKET_CELLS = 1024, MAX_DATA = 512 * 1024;

  static final class State {
    int revision, x, z, y, cursor, depth, reads, known, pieceCursor;
    int[] colors = new int[SIZE * SIZE], pieces = new int[0];
    byte[] flags = new byte[SIZE * SIZE];
    final BitSet unknown = new BitSet(SIZE * SIZE);
    final BitSet dirty = new BitSet(SIZE * SIZE);
    boolean started, done, simplified;
    long retryAt;
    int bytes;
  }

  private PlanPreview() {}

  static void reset(StructureJobs.Job job) {
    State old = job.previewState;
    State state = new State();
    state.revision = old == null ? 0 : old.revision + 1;
    state.x = old == null ? (job.body.minX() + job.body.maxX()) / 2 - SIZE / 2 : old.x;
    state.z = old == null ? (job.body.minZ() + job.body.maxZ()) / 2 - SIZE / 2 : old.z;
    state.y = clamp(job.previewY, job.region.minY(), job.region.maxY());
    state.unknown.set(0, SIZE * SIZE);
    state.pieces = pieceProjection(job, state);
    job.previewState = state;
    job.previewDone = false;
  }

  private static int[] pieceProjection(StructureJobs.Job job, State state) {
    int margin = Config.STRUCTURE_MARGIN.get();
    // 轮廓分包发送，不能把大量分段和容器标记挤进同一个32KiB包。
    if ((long) job.footprint.size() * 16 + SIZE * SIZE * 9 <= MAX_DATA) {
      int[] result = new int[job.footprint.size() * 4];
      int i = 0;
      for (var area : job.footprint) {
        result[i++] = area.minX() + margin;
        result[i++] = area.minZ() + margin;
        result[i++] = area.maxX() - margin;
        result[i++] = area.maxZ() - margin;
      }
      return result;
    }
    // 极端数量的重叠分段采用完整候选区块示意，不静默丢弃后半栋建筑。
    state.simplified = true;
    int[] result = new int[job.plan.size() * 4];
    int i = 0;
    for (var chunk : job.plan) {
      result[i++] = chunk.getMinBlockX();
      result[i++] = chunk.getMinBlockZ();
      result[i++] = chunk.getMaxBlockX();
      result[i++] = chunk.getMaxBlockZ();
    }
    return result;
  }

  static void request(ServerPlayer player, StructureJobs.Job job, int revision,
      int centerX, int centerZ, int height) {
    if (job.previewState == null) reset(job);
    State current = job.previewState;
    if (revision <= current.revision) return;
    job.previewY = clamp(height, job.region.minY(), job.region.maxY());
    reset(job);
    State state = job.previewState;
    state.revision = revision;
    state.x = clamp(centerX, job.body.minX(), job.body.maxX()) - SIZE / 2;
    state.z = clamp(centerZ, job.body.minZ(), job.body.maxZ()) - SIZE / 2;
    sendHeader(player, job);
  }

  static void request(ServerPlayer player, StructureJobs.Job job, int action, int value) {
    if (job.previewState == null) reset(job);
    State state = job.previewState;
    if (action == 25)
      request(player, job, state.revision + 1, state.x + SIZE / 2, state.z + SIZE / 2, value);
  }

  static void snapshot(StructureJobs.Job job, CompoundTag tag) {
    if (job.previewState == null) reset(job);
    State state = job.previewState;
    tag.putBoolean("Map2D", true);
    tag.putInt("MapRevision", state.revision);
    tag.putInt("MapX", state.x);
    tag.putInt("MapZ", state.z);
    tag.putInt("MapY", state.y);
    tag.putInt("MapSize", SIZE);
    tag.putInt("MapKnown", state.known);
    tag.putInt("MapReads", state.reads);
    tag.putBoolean("MapDone", state.done);
    tag.putBoolean("MapLayoutSimplified", state.simplified);
  }

  private static void sendHeader(ServerPlayer player, StructureJobs.Job job) {
    if (player.connection == null) return;
    CompoundTag tag = packet(job);
    snapshot(job, tag);
    tag.putBoolean("MapHeader", true);
    Net.structure(player, tag);
  }

  static void tick(ServerPlayer player, StructureJobs.Job job, long deadline) {
    if (!job.viewing || player.connection == null || System.nanoTime() >= deadline) return;
    if (job.previewState == null) reset(job);
    State state = job.previewState;
    if (!state.started) { state.started = true; sendHeader(player, job); }
    sendPieces(player, job, state);
    boolean wasDone = state.done;
    sample(job, deadline);
    sendPixels(player, job, state);
    if (state.done && !wasDone) sendHeader(player, job);
  }

  /** 采样与发送分开：测试可直接核对真实世界读取，自动收取不调用此方法。 */
  static void sample(StructureJobs.Job job, long deadline) {
    if (job.previewState == null) reset(job);
    State state = job.previewState;
    if (state.done) {
      if (state.unknown.isEmpty() || job.level.getGameTime() < state.retryAt) return;
      state.cursor = state.unknown.nextSetBit(0);
      state.done = false;
    }
    int visits = 0;
    while (state.cursor < SIZE * SIZE && visits++ < PACKET_CELLS
        && state.reads < MAX_READS && System.nanoTime() < deadline) {
      int index = state.cursor;
      if (!state.unknown.get(index)) { state.cursor++; continue; }
      int x = state.x + index % SIZE, z = state.z + index / SIZE;
      // getChunkNow绝不触发加载，更不生成新区块；未知保持独立状态。
      var chunk = job.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
      if (chunk == null) { state.depth = 0; state.cursor++; continue; }
      BlockPos at = new BlockPos(x, state.y - state.depth, z);
      if (at.getY() < job.region.minY()) { finishCell(state, OPEN, 0xff151b22); continue; }
      BlockState block = chunk.getBlockState(at);
      state.reads++;
      if (!block.getFluidState().isEmpty()) {
        finishCell(state, LIQUID, color(job, at, block, 0x366f9b));
      } else if (!block.isAir()) {
        int type = state.depth == 0 ? WALL : FLOOR;
        int rgb = color(job, at, block, 0x929087);
        finishCell(state, type, type == WALL ? darken(rgb) : rgb);
      } else if (state.depth == DEPTH) {
        finishCell(state, OPEN, 0xff151b22);
      } else {
        state.depth++;
      }
    }
    if (state.cursor >= SIZE * SIZE || state.reads >= MAX_READS) {
      state.done = true;
      state.retryAt = job.level.getGameTime() + 20;
      job.previewDone = true;
    }
  }

  private static int color(StructureJobs.Job job, BlockPos at, BlockState state, int fallback) {
    int rgb = state.getMapColor(job.level, at).col;
    return 0xff000000 | (rgb == 0 ? fallback : rgb);
  }

  private static int darken(int rgb) {
    return 0xff000000 | ((rgb >> 16 & 255) * 34 / 100 << 16)
        | ((rgb >> 8 & 255) * 34 / 100 << 8) | ((rgb & 255) * 34 / 100);
  }

  private static void finishCell(State state, int type, int color) {
    int index = state.cursor++;
    state.colors[index] = color;
    state.flags[index] = (byte) (type | state.depth << 4);
    state.unknown.clear(index);
    state.dirty.set(index);
    state.known++;
    state.depth = 0;
  }

  private static void sendPixels(ServerPlayer player, StructureJobs.Job job, State state) {
    int count = Math.min(PACKET_CELLS, state.dirty.cardinality());
    if (count == 0 || state.bytes + count * 9 > MAX_DATA) return;
    int[] indices = new int[count], colors = new int[count];
    byte[] flags = new byte[count];
    int index = state.dirty.nextSetBit(0);
    for (int i = 0; i < count; i++) {
      indices[i] = index;
      colors[i] = state.colors[index];
      flags[i] = state.flags[index];
      state.dirty.clear(index);
      index = state.dirty.nextSetBit(index + 1);
    }
    // 稀疏索引避免未知区块稍后就绪时重复传送已经完成的整片图像。
    CompoundTag tag = packet(job);
    tag.putInt("MapRevision", state.revision);
    tag.putIntArray("MapIndices", indices);
    tag.putIntArray("MapColors", colors);
    tag.putByteArray("MapFlags", flags);
    Net.structure(player, tag);
    state.bytes += count * 9;
  }

  private static void sendPieces(ServerPlayer player, StructureJobs.Job job, State state) {
    if (state.pieceCursor >= state.pieces.length) return;
    int stop = Math.min(state.pieces.length, state.pieceCursor + 2048);
    CompoundTag tag = packet(job);
    tag.putInt("MapRevision", state.revision);
    tag.putInt("MapPieceOffset", state.pieceCursor);
    tag.putInt("MapPieceTotal", state.pieces.length);
    tag.putIntArray("MapPieces", Arrays.copyOfRange(state.pieces, state.pieceCursor, stop));
    Net.structure(player, tag);
    state.bytes += (stop - state.pieceCursor) * 4;
    state.pieceCursor = stop;
  }

  private static CompoundTag packet(StructureJobs.Job job) {
    CompoundTag tag = new CompoundTag();
    tag.putUUID("Nonce", job.nonce);
    return tag;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
