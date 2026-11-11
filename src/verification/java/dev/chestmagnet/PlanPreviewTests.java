package dev.chestmagnet;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class PlanPreviewTests {
  @GameTest(template = "empty")
  public static void realSliceAndUnknownNeverLoad(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(1, 12, 1));
    for (int x = 0; x < 4; x++)
      for (int down = 0; down <= PlanPreview.DEPTH; down++)
        level.setBlockAndUpdate(origin.offset(x, -down, 0), Blocks.AIR.defaultBlockState());
    level.setBlockAndUpdate(origin.below(3), Blocks.OAK_PLANKS.defaultBlockState());
    level.setBlockAndUpdate(origin.east(), Blocks.STONE.defaultBlockState());
    level.setBlockAndUpdate(origin.east(3).below(), Blocks.WATER.defaultBlockState());
    var job = new StructureJobs.Job();
    job.level = level;
    job.body = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
        origin.getX() + 3, origin.getY() + 1, origin.getZ());
    job.region = new BoundingBox(origin.getX() - 16, level.getMinBuildHeight(), origin.getZ() - 16,
        origin.getX() + 19, level.getMaxBuildHeight() - 1, origin.getZ() + 16);
    StructureJobs.buildFootprint(job, List.of(job.body));
    job.previewY = origin.getY();
    PlanPreview.reset(job);
    for (int n = 0; n < 300 && !job.previewState.done; n++) PlanPreview.sample(job, Long.MAX_VALUE);
    var map = job.previewState;
    int first = (origin.getZ() - map.z) * PlanPreview.SIZE + origin.getX() - map.x;
    helper.assertTrue((map.flags[first] & 15) == PlanPreview.FLOOR
        && ((map.flags[first] & 255) >> 4) == 3, "real air column finds floor at depth three");
    helper.assertTrue((map.flags[first + 1] & 15) == PlanPreview.WALL, "real solid cell stays wall");
    helper.assertTrue((map.flags[first + 2] & 15) == PlanPreview.OPEN, "eight-level empty well stays open");
    helper.assertTrue((map.flags[first + 3] & 15) == PlanPreview.LIQUID, "real water gets liquid color");
    helper.assertTrue(map.reads <= PlanPreview.MAX_READS, "hard per-view read bound respected");
    var snapshot = new CompoundTag();
    PlanPreview.snapshot(job, snapshot);
    helper.assertTrue(!snapshot.contains("Items") && !snapshot.contains("BlockEntityTag"),
        "preview metadata contains no container inventory or block entity NBT");
    // 使用远离测试世界的冷区块，证明采样器不会为了预览同步加载或生成。
    map.x = 2_000_000;
    map.z = 2_000_000;
    map.cursor = map.depth = map.reads = map.known = 0;
    map.done = false;
    map.unknown.set(0, PlanPreview.SIZE * PlanPreview.SIZE);
    int coldX = map.x >> 4, coldZ = map.z >> 4;
    helper.assertTrue(level.getChunkSource().getChunkNow(coldX, coldZ) == null, "fixture cold before preview");
    for (int n = 0; n < 20 && !map.done; n++) PlanPreview.sample(job, Long.MAX_VALUE);
    helper.assertTrue(map.reads == 0 && map.known == 0
        && level.getChunkSource().getChunkNow(coldX, coldZ) == null,
        "unknown stays unknown with zero block reads and no forced chunk load");
    helper.succeed();
  }
}
