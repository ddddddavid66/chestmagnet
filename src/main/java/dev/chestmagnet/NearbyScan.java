package dev.chestmagnet;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/** 只缓存坐标和游标，避免跨 tick 持有卸载区块、实体或库存。 */
final class NearbyScan {
  record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
    boolean contains(BlockPos p) {
      return p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1
          && p.getZ() >= z0 && p.getZ() <= z1;
    }

    boolean overlapsChunk(ChunkPos c) {
      return c.getMaxBlockX() >= x0 && c.getMinBlockX() <= x1
          && c.getMaxBlockZ() >= z0 && c.getMinBlockZ() <= z1;
    }
  }

  private Box box;
  private long blocksDue, specialsDue, entitiesDue;
  private final Deque<ChunkPos> blocks = new ArrayDeque<>();
  private final Deque<ChunkPos> specials = new ArrayDeque<>();
  private final Deque<UUID> entities = new ArrayDeque<>();
  private ChunkPos blockChunk, specialChunk;
  private Box specialBox;
  private Iterator<BlockPos> positions = Collections.emptyIterator();
  private int section, cell;

  void step(ServerPlayer player, Box current, boolean locked, boolean frames, long deadline,
      Consumer<Object> found) {
    var level = player.serverLevel();
    long now = level.getGameTime();
    boolean moved = !current.equals(box);
    if (moved) {
      // 保留重叠区块游标；新范围中未检查的部分最迟在下一轮复查。
      if (box != null) {
        var previous = box;
        if (!blocks.isEmpty() || blockChunk != null)
          for (var c : chunks(current, player.blockPosition()))
            if (!previous.overlapsChunk(c)) blocks.addLast(c);
        if (locked && (!specials.isEmpty() || specialChunk != null))
          for (var c : chunks(current, player.blockPosition()))
            if (!previous.overlapsChunk(c)) specials.addLast(c);
      }
      blocks.removeIf(c -> !current.overlapsChunk(c));
      specials.removeIf(c -> !current.overlapsChunk(c));
      if (blockChunk != null && !current.overlapsChunk(blockChunk)) {
        blockChunk = null;
        positions = Collections.emptyIterator();
      }
      box = current;
    }
    if (blockChunk == null && blocks.isEmpty() && (moved || now >= blocksDue)) {
      blocks.addAll(chunks(box, player.blockPosition()));
      blocksDue = now + 10;
    }
    if (locked && specialChunk == null && specials.isEmpty() && (moved || now >= specialsDue)) {
      specials.addAll(chunks(box, player.blockPosition()));
      specialsDue = now + 10;
    } else if (!locked) {
      specialChunk = null;
      specials.clear();
      specialsDue = now;
    }

    int budget = Config.SCAN.get();
    if (entities.isEmpty() && now >= entitiesDue && System.nanoTime() < deadline) {
      var bounds = new AABB(box.x0, box.y0, box.z0, box.x1 + 1, box.y1 + 1, box.z1 + 1);
      for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, bounds,
          e -> Sources.eligible(e)
              && (frames || !(e instanceof net.minecraft.world.entity.decoration.ItemFrame))))
        entities.addLast(entity.getUUID());
      entitiesDue = now + 5;
    }
    // 给方块发现留出候选预算；密集的实体查询可跨 tick 继续。
    int entitySlice = Math.max(1, budget / 4);
    if (budget == 1 && (now & 1) == 0) entitySlice = 0;
    while (entitySlice-- > 0 && !entities.isEmpty() && System.nanoTime() < deadline) {
      budget--;
      var entity = level.getEntity(entities.removeFirst());
      if (entity != null && entity.isAlive() && box.contains(entity.blockPosition())) found.accept(entity);
    }
    LevelChunk loaded = null;
    while (budget > 0 && System.nanoTime() < deadline) {
      if (blockChunk == null) {
        blockChunk = blocks.pollFirst();
        if (blockChunk == null) break;
        budget--;
        loaded = loaded(player, blockChunk);
        positions = loaded == null ? Collections.emptyIterator()
            : new ArrayList<>(loaded.getBlockEntitiesPos()).iterator();
      }
      if (!positions.hasNext()) {
        blockChunk = null;
        loaded = null;
        continue;
      }
      if (budget == 0) break;
      budget--;
      var pos = positions.next();
      if (!box.contains(pos)) continue;
      if (loaded == null) loaded = loaded(player, blockChunk);
      if (loaded == null) {
        blockChunk = null;
        positions = Collections.emptyIterator();
        continue;
      }
      // 只实例化范围内存档尚未加载的方块实体。
      var be = loaded.getBlockEntity(pos);
      if (Sources.eligible(be)) found.accept(be);
    }
  }

  void specials(ServerPlayer player, boolean locked, long deadline, Consumer<Object> found) {
    if (!locked || box == null) return;
    int budget = 512;
    LevelChunk chunk = null;
    while (budget-- > 0 && System.nanoTime() < deadline) {
      if (specialChunk == null) {
        specialChunk = specials.pollFirst();
        if (specialChunk == null) return;
        specialBox = box;
        section = Integer.MAX_VALUE;
        cell = 0;
      }
      if (chunk == null) chunk = loaded(player, specialChunk);
      if (chunk == null || !box.overlapsChunk(specialChunk)) {
        specialChunk = null;
        chunk = null;
        continue;
      }
      var scan = specialBox;
      int min = Math.max(0, (scan.y0 - chunk.getMinBuildHeight()) >> 4);
      int max = Math.min(chunk.getSectionsCount() - 1, (scan.y1 - chunk.getMinBuildHeight()) >> 4);
      if (section > max) {
        section = max;
        cell = 0;
      }
      if (section < min) {
        specialChunk = null;
        chunk = null;
        continue;
      }
      var contents = chunk.getSection(section);
      int yBase = chunk.getMinBuildHeight() + section * 16;
      int x0 = Math.max(scan.x0, specialChunk.getMinBlockX());
      int z0 = Math.max(scan.z0, specialChunk.getMinBlockZ());
      int y0 = Math.max(scan.y0, yBase);
      int width = Math.min(scan.x1, specialChunk.getMaxBlockX()) - x0 + 1;
      int depth = Math.min(scan.z1, specialChunk.getMaxBlockZ()) - z0 + 1;
      int height = Math.min(scan.y1, yBase + 15) - y0 + 1;
      if (cell >= width * depth * height
          || (cell == 0 && !contents.getStates().maybeHas(SourceExtras::special))) {
        section--;
        cell = 0;
        continue;
      }
      int i = cell++;
      int x = x0 + i % width, z = z0 + i / width % depth, y = y0 + i / (width * depth);
      if (SourceExtras.special(contents.getBlockState(x & 15, y & 15, z & 15))) {
        var pos = new BlockPos(x, y, z);
        if (box.contains(pos)) found.accept(new SourceExtras.Special(pos));
      }
    }
  }

  private static LevelChunk loaded(ServerPlayer player, ChunkPos c) {
    return player.serverLevel().getChunkSource().getChunkNow(c.x, c.z);
  }

  private static List<ChunkPos> chunks(Box b, BlockPos at) {
    var result = new ArrayList<ChunkPos>();
    for (int x = b.x0 >> 4; x <= (b.x1 >> 4); x++)
      for (int z = b.z0 >> 4; z <= (b.z1 >> 4); z++) result.add(new ChunkPos(x, z));
    result.sort(Comparator.comparingInt(c -> {
      int dx = Math.max(0, Math.max(c.getMinBlockX() - at.getX(), at.getX() - c.getMaxBlockX()));
      int dz = Math.max(0, Math.max(c.getMinBlockZ() - at.getZ(), at.getZ() - c.getMaxBlockZ()));
      return dx * dx + dz * dz;
    }));
    return result;
  }
}
