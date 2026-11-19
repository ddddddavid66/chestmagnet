package dev.rsqa124;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.block.entities.TileTicker;
import noobanidus.mods.lootr.event.HandleChunk;

/** 只观察地图原生写入。不要用 getBlockEntity 让探针先实例化存档方块实体。 */
public final class FreshTreasureTrace {
  public static final String ARM = System.getProperty("qa.freshTreasure", "");
  public static final ResourceLocation TABLE = new ResourceLocation("minecraft", "chests/buried_treasure");
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("fresh-treasure");
  private static final Set<BlockPos> WATCH = ConcurrentHashMap.newKeySet();
  private static final Set<BlockPos> LOOTR_SEEN = ConcurrentHashMap.newKeySet();
  private static final Field RAW_TABLE = java.util.Arrays.stream(RandomizableContainerBlockEntity.class.getDeclaredFields())
      .filter(f -> f.getType() == ResourceLocation.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
      .findFirst().orElseThrow();

  static { RAW_TABLE.setAccessible(true); }

  public static void mark(BlockEntity be, ResourceLocation table) {
    if (!ARM.isEmpty() && TABLE.equals(table) && WATCH.add(be.getBlockPos().immutable()))
      log(be.getBlockPos(), "NATIVE_TABLE", describe(be), be.getLevel() instanceof ServerLevel level ? level : null);
  }

  public static boolean watching(BlockPos pos) {
    return !ARM.isEmpty() && WATCH.contains(pos);
  }

  public static String describe(BlockEntity be) {
    if (be == null) return "-";
    return ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType()) + ":" + be.getClass().getSimpleName()
        + ":table=" + (be instanceof ILootBlockEntity ? read(be, "savedLootTable") : rawTable(be))
        + (be instanceof ILootBlockEntity ? ":lootr-id=" + read(be, "tileId") : "");
  }

  public static ResourceLocation rawTable(BlockEntity be) {
    try { return be instanceof RandomizableContainerBlockEntity ? (ResourceLocation) RAW_TABLE.get(be) : null; }
    catch (IllegalAccessException e) { throw new IllegalStateException("QA loot table", e); }
  }

  private static Object read(Object instance, String name) {
    // saveWithoutMetadata 会初始化尚未存在的 Lootr UUID；探针必须只读。
    for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
      try {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
      } catch (NoSuchFieldException ignored) {
        // Quark 等可使用 Lootr 实体的子类。
      } catch (ReflectiveOperationException e) { throw new IllegalStateException("QA field " + name, e); }
    }
    return "unsupported";
  }

  public static void write(LevelChunk chunk, BlockPos pos, String event) {
    if (!(chunk.getLevel() instanceof ServerLevel level) || !watching(pos)) return;
    var be = chunk.getBlockEntities().get(pos);
    if (be instanceof ILootBlockEntity) LOOTR_SEEN.add(pos.immutable());
    var block = ForgeRegistries.BLOCKS.getKey(chunk.getBlockState(pos).getBlock());
    if (new ResourceLocation("minecraft", "chest").equals(block) && LOOTR_SEEN.contains(pos))
      event = "LOOTR_TO_VANILLA_" + event;
    log(pos, event, block + " " + describe(be), level);
  }

  public static void unpack(RandomizableContainerBlockEntity be, String event) {
    if (watching(be.getBlockPos()) && be.getLevel() instanceof ServerLevel level)
      log(be.getBlockPos(), event, describe(be), level);
  }

  public static void log(BlockPos pos, String action, String data, ServerLevel level) {
    // 栈仅记录目标类型的埋宝坐标，不进入计时基准。
    LOG.info("FRESH_WRITE arm={} at={} level={} tick={} {} {}", ARM, pos.toShortString(),
        level == null ? "generation" : level.dimension().location(), level == null ? -1 : level.getGameTime(),
        action, data, new Exception("QA-only writer"));
  }

  public static String sample(ServerLevel level, int x, int z) {
    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
    if (chunk == null) return "not_full";
    var found = new java.util.ArrayList<String>();
    for (var pos : chunk.getBlockEntitiesPos()) {
      if (pos.getX() != x || pos.getZ() != z) continue;
      var be = chunk.getBlockEntities().get(pos);
      var state = ForgeRegistries.BLOCKS.getKey(chunk.getBlockState(pos).getBlock());
      var raw = chunk.getBlockEntityNbt(pos);
      if (be != null || raw != null)
        found.add(pos.toShortString() + ":" + state + ":" + (be == null
            ? "pending:" + raw.getString("id") + ":table=" + raw.getString("LootTable") : describe(be))
            + (be instanceof ILootBlockEntity ? "" : ":queue=" + queue(level, pos)));
      if (be instanceof ILootBlockEntity) LOOTR_SEEN.add(pos.immutable());
    }
    found.sort(String::compareTo);
    int ready = 0, tracked = 0;
    var center = new ChunkPos(x >> 4, z >> 4);
    synchronized (HandleChunk.LOADED_CHUNKS) {
      var known = HandleChunk.LOADED_CHUNKS.get(level.dimension());
      for (int dz = -2; dz <= 2; dz++) {
        for (int dx = -2; dx <= 2; dx++) {
          if (level.getChunkSource().hasChunk(center.x + dx, center.z + dz)) ready++;
          if (known != null && known.contains(new ChunkPos(center.x + dx, center.z + dz))) tracked++;
        }
      }
    }
    return "ready25=" + ready + " tracked25=" + tracked + " " + found;
  }

  static String queue(ServerLevel level, BlockPos pos) {
    try {
      synchronized (staticQueueField("listLock")) {
        for (String name : java.util.List.of("tileEntries", "pendingEntries")) {
          for (var it : (Set<?>)staticQueueField(name)) {
            if (it instanceof TileTicker.Entry entry && entry.getDimension().equals(level.dimension())
                && entry.getPosition().equals(pos)) return name + "-age" + entry.age(level.getServer());
          }
        }
      }
      return "absent";
    } catch (ReflectiveOperationException e) { throw new IllegalStateException("QA Lootr 0.7.35.94 queue", e); }
  }

  private static Object staticQueueField(String name) throws ReflectiveOperationException {
    var field = TileTicker.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }
}
