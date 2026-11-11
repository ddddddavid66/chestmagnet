package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

/** 只验证新的覆盖集合和热区块调度，不以单元断言冒充整合包性能数据。 */
@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class StructureSpeedTests {
  private static final TicketType<UUID> PRELOAD =
      TicketType.create("chestmagnet_speed_fixture", Comparator.comparing(UUID::toString));

  @GameTest(template = "empty")
  public static void pieceUnionFullHeight(GameTestHelper h) {
    var j = new StructureJobs.Job();
    j.level = h.getLevel();
    j.region = new BoundingBox(-16, j.level.getMinBuildHeight(), -16,
        115, j.level.getMaxBuildHeight() - 1, 115);
    var first = new BoundingBox(0, 50, 0, 3, 54, 3);
    var second = new BoundingBox(96, 110, 96, 99, 114, 99);
    StructureJobs.buildFootprint(j, List.of(first, first, second));
    h.assertTrue(StructureJobs.contains(j, new BlockPos(1, j.region.minY(), 1)),
        "deep separated level included under piece");
    h.assertTrue(StructureJobs.contains(j, new BlockPos(97, j.region.maxY(), 97)),
        "all dimension height included above other piece");
    h.assertTrue(!StructureJobs.contains(j, new BlockPos(48, 60, 48)),
        "empty gap inside outer envelope is not scanned");
    h.assertTrue(!StructureJobs.contains(j, new BlockPos(20, 60, 1)),
        "outside piece safety margin is not scanned");
    h.assertTrue(j.total == 18 && j.plan.size() == 18,
        "overlapping piece projections deduplicate chunk count");
    h.assertTrue(j.total < 81, "union avoids empty enclosing rectangle chunks");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 160)
  public static void hotChunksDoNotWaitFiveTicks(GameTestHelper h) {
    var level = h.getLevel();
    var first = new ChunkPos(h.absolutePos(new BlockPos(1, 1, 1)));
    var chunks = List.of(first, new ChunkPos(first.x + 1, first.z));
    var preload = UUID.randomUUID();
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    Data.tag(magnet).putBoolean("CollectLocked", false);
    var player = FakePlayerFactory.get(level,
        new GameProfile(UUID.randomUUID(), "HotChunkTest"));
    player.getInventory().clearContent();
    player.setItemInHand(InteractionHand.MAIN_HAND, magnet);
    var j = new StructureJobs.Job();
    j.level = level;
    j.dimension = level.dimension().location().toString();
    j.result = new CompoundTag();
    j.result.putString("Structure", "chestmagnet:hot_fixture");
    j.start = first;
    j.magnet = Data.id(magnet);
    j.running = true;
    j.total = 2;
    j.plan.addAll(chunks);
    j.waiting.addAll(chunks);
    j.lastRequest = level.getGameTime();
    int y = h.absolutePos(new BlockPos(1, 1, 1)).getY();
    var points = chunks.stream().map(cp -> new BlockPos(cp.getMinBlockX() + 1, y,
        cp.getMinBlockZ() + 1)).toList();
    j.region = new BoundingBox(points.get(0).getX(), level.getMinBuildHeight(),
        points.get(0).getZ(), points.get(1).getX(), level.getMaxBuildHeight() - 1,
        points.get(1).getZ());
    j.body = new BoundingBox(points.get(0).getX(), y, points.get(0).getZ(),
        points.get(1).getX(), y + 2, points.get(1).getZ());
    j.previewY = y + 2;
    j.footprint = points.stream().map(at -> new BoundingBox(at.getX(), level.getMinBuildHeight(),
        at.getZ(), at.getX(), level.getMaxBuildHeight() - 1, at.getZ())).toList();
    for (var cp : chunks) {
      level.getChunk(cp.x, cp.z); // 仅测试夹具同步预载；产品调度从不同步等世界生成。
      level.getChunkSource().addRegionTicket(PRELOAD, cp, 0, preload);
    }
    for (var at : points) {
      level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
      ((BarrelBlockEntity) level.getBlockEntity(at)).setItem(0, new ItemStack(Items.DIAMOND));
    }
    h.runAfterDelay(60, () -> {
      try {
        for (var cp : chunks)
          h.assertTrue(level.areEntitiesLoaded(cp.toLong()), "fixture entity data ready");
        j.lastRequest = level.getGameTime();
        StructureJobs.collect(player, j, Long.MAX_VALUE);
        h.assertTrue(j.done == 2 && j.complete, "two loaded chunks completed in one call");
        h.assertTrue(j.loadRequests == 0 && j.tickets.isEmpty(),
            "no cold request or retained ticket for already loaded chunks");
        for (var at : points)
          h.assertTrue(((BarrelBlockEntity) level.getBlockEntity(at)).isEmpty(),
              "both loaded source inventories transferred");
        h.succeed();
      } finally {
        StructureJobs.release(j);
        Faults.clearContext();
        for (var cp : chunks) level.getChunkSource().removeRegionTicket(PRELOAD, cp, 0, preload);
      }
    });
  }
}
