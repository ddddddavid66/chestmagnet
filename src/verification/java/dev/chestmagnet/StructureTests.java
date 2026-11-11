package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class StructureTests {
  @GameTest(template = "empty", timeoutTicks = 2400)
  public static void naturalStructures(GameTestHelper h) {
    var level =
        h.getLevel()
            .getServer()
            .getLevel(
                ResourceKey.create(
                    Registries.DIMENSION, new ResourceLocation("chestmagnet:natural")));
    var warm = new java.util.ArrayList<ChunkPos>();
    var token = java.util.UUID.randomUUID();
    for (String name : List.of("jungle_pyramid", "desert_pyramid", "pillager_outpost")) {
      var holder =
          level
              .registryAccess()
              .registryOrThrow(Registries.STRUCTURE)
              .getHolderOrThrow(
                  ResourceKey.create(
                      Registries.STRUCTURE, new ResourceLocation("minecraft", name)));
      var located =
          level
              .getChunkSource()
              .getGenerator()
              .findNearestMapStructure(
                  level, HolderSet.direct(holder), new BlockPos(0, 64, 0), 100, false);
      var cp = new ChunkPos(located.getFirst());
      var start = level.getChunk(cp.x, cp.z).getStartForStructure(holder.value());
      var box = StructureJobs.bounds(start);
      for (int x = (box.minX() - 16) >> 4; x <= (box.maxX() + 16) >> 4; x++)
        for (int z = (box.minZ() - 16) >> 4; z <= (box.maxZ() + 16) >> 4; z++) {
          var at = new ChunkPos(x, z);
          level.getChunk(x, z);
          level.getChunkSource().addRegionTicket(StructureJobs.TICKET, at, 0, token);
          warm.add(at);
        }
    }
    // 实体读盘在后续服务器tick提交，测试不能在同一tick忙等冒充已读盘。
    h.runAfterDelay(
        60,
        () -> {
          try {
            verifyNaturalStructures(h);
          } finally {
            for (var at : warm)
              level.getChunkSource().removeRegionTicket(StructureJobs.TICKET, at, 0, token);
          }
        });
  }

  private static void verifyNaturalStructures(GameTestHelper h) {
    var level =
        h.getLevel()
            .getServer()
            .getLevel(
                ResourceKey.create(
                    Registries.DIMENSION, new ResourceLocation("chestmagnet", "natural")));
    h.assertTrue(level != null, "natural dimension");
    for (String name : List.of("jungle_pyramid", "desert_pyramid", "pillager_outpost")) {
      var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
      var key = ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft", name));
      var holder = registry.getHolderOrThrow(key);
      var located =
          level
              .getChunkSource()
              .getGenerator()
              .findNearestMapStructure(
                  level, HolderSet.direct(holder), new BlockPos(0, 64, 0), 100, false);
      h.assertTrue(located != null, "natural locate " + name);
      var at = new ChunkPos(located.getFirst());
      var chunk = level.getChunk(at.x, at.z);
      var start = chunk.getStartForStructure(holder.value());
      h.assertTrue(start != null && start.isValid(), "registered natural start " + name);
      var j = new StructureJobs.Job();
      j.level = level;
      j.result = new net.minecraft.nbt.CompoundTag();
      j.result.putString("Structure", "minecraft:" + name);
      j.start = at;
      j.structure = holder.value();
      StructureJobs.geometry(j);
      for (int x = j.region.minX() >> 4; x <= j.region.maxX() >> 4; x++)
        for (int z = j.region.minZ() >> 4; z <= j.region.maxZ() >> 4; z++) level.getChunk(x, z);
      StructureJobs.geometry(j);
      h.assertTrue(
          j.region.minY() == level.getMinBuildHeight()
              && j.region.maxY() == level.getMaxBuildHeight() - 1,
          "full dimension height");

      if (name.equals("jungle_pyramid")) {
        var fixture = new net.minecraft.nbt.CompoundTag();
        fixture.putUUID("Nonce", UUID.randomUUID());
        fixture.putString("Structure", "minecraft:jungle_pyramid");
        fixture.putString("Status", "自然生成结构快照 · 渲染测试，不是实时联网会话");
        fixture.putBoolean("Open", true);
        fixture.putIntArray(
            "Body",
            new int[] {
              j.body.minX(),
              j.body.minY() - 16,
              j.body.minZ(),
              j.body.maxX(),
              j.body.maxY(),
              j.body.maxZ()
            });
        fixture.putIntArray(
            "Region",
            new int[] {
              j.region.minX(),
              j.region.minY(),
              j.region.minZ(),
              j.region.maxX(),
              j.region.maxY(),
              j.region.maxZ()
            });
        fixture.putInt("Slice", j.body.maxY());
        var positions = new java.util.ArrayList<Long>();
        var states = new net.minecraft.nbt.ListTag();
        for (var pos :
            BlockPos.betweenClosed(
                j.body.minX(),
                j.body.minY() - 16,
                j.body.minZ(),
                j.body.maxX(),
                j.body.maxY(),
                j.body.maxZ())) {
          var state = level.getBlockState(pos);
          if (!state.isAir()) {
            positions.add(pos.asLong());
            states.add(net.minecraft.nbt.NbtUtils.writeBlockState(state));
          }
        }
        fixture.putLongArray("Positions", positions);
        fixture.put("BlockStates", states);
        try {
          net.minecraft.nbt.NbtIo.writeCompressed(
              fixture, new java.io.File("structure-preview.nbt"));
        } catch (java.io.IOException ex) {
          throw new RuntimeException(ex);
        }
      }
      var player =
          FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "StructureTest"));
      player.setPos(j.body.minX(), j.body.maxY(), j.body.minZ());
      var magnet = new ItemStack(ChestMagnet.MAGNET.get());
      int containers = 0;
      for (int x = j.region.minX() >> 4; x <= j.region.maxX() >> 4; x++)
        for (int z = j.region.minZ() >> 4; z <= j.region.maxZ() >> 4; z++)
          for (var pos : level.getChunk(x, z).getBlockEntitiesPos()) {
            if (!j.region.isInside(pos)) continue;
            var be = level.getBlockEntity(pos);
            if (!Sources.eligible(be)) continue;
            containers++;
            var inv = Sources.inventory(player, be);
            if (inv != null)
              for (int slot = 0; slot < inv.getSlots(); slot++) {
                var sample = inv.extractItem(slot, 64, true);
                if (!sample.isEmpty()) {
                  var target = Destinations.get(player, 0);
                  int accepted = sample.getCount() - target.insert(sample, true).getCount();
                  if (accepted > 0) Transfer.move(inv, slot, accepted, target, magnet);
                }
              }
          }
      h.assertTrue(
          containers >= (name.equals("desert_pyramid") ? 4 : name.equals("jungle_pyramid") ? 2 : 1),
          "all natural chest positions " + name + " got " + containers);
      var deep = new BlockPos(j.body.minX(), level.getMinBuildHeight() + 8, j.body.minZ());
      level.setBlockAndUpdate(deep, Blocks.BARREL.defaultBlockState());
      ((BarrelBlockEntity) level.getBlockEntity(deep)).setItem(0, new ItemStack(Items.DIAMOND));
      h.assertTrue(j.region.isInside(deep), "arbitrary deeper separated floor included");
      var outside = new BlockPos(j.region.maxX() + 1, deep.getY(), deep.getZ());
      h.assertTrue(!j.region.isInside(outside), "outside XZ excluded");

      var outsideBarrel = outside;
      level.setBlockAndUpdate(outsideBarrel, Blocks.BARREL.defaultBlockState());
      ((BarrelBlockEntity) level.getBlockEntity(outsideBarrel))
          .setItem(0, new ItemStack(Items.EMERALD));
      var excluded = deep.offset(1, 0, 0);
      level.setBlockAndUpdate(excluded, Blocks.BARREL.defaultBlockState());
      ((BarrelBlockEntity) level.getBlockEntity(excluded))
          .setItem(0, new ItemStack(Items.NETHER_STAR));
      Data.toggle(
          magnet, Sources.of(level.getBlockEntity(excluded)).key(player), "excluded fixture");
      player.getInventory().clearContent();
      player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, magnet);
      j.running = true;
      j.magnet = Data.id(magnet);
      for (int x = j.region.minX() >> 4; x <= j.region.maxX() >> 4; x++)
        for (int z = j.region.minZ() >> 4; z <= j.region.maxZ() >> 4; z++)
          j.waiting.add(new ChunkPos(x, z));
      j.total = j.waiting.size();
      for (int tries = 0; tries < 1000 && !j.paused; tries++) {
        j.lastRequest = -100000;
        StructureJobs.collect(player, j, Long.MAX_VALUE);
      }
      h.assertTrue(
          j.done == j.total && j.tickets.isEmpty(),
          "all chunk task complete and tickets released " + name + " " + j.status);
      h.assertTrue(
          ((BarrelBlockEntity) level.getBlockEntity(deep)).getItem(0).isEmpty(),
          "deep arbitrary floor transferred");
      h.assertTrue(
          ((BarrelBlockEntity) level.getBlockEntity(excluded)).getItem(0).is(Items.NETHER_STAR),
          "exclusion respected");
      h.assertTrue(
          ((BarrelBlockEntity) level.getBlockEntity(outsideBarrel)).getItem(0).is(Items.EMERALD),
          "outside untouched");

      ((BarrelBlockEntity) level.getBlockEntity(deep)).setItem(0, new ItemStack(Items.DIAMOND, 8));
      for (int slot = 1; slot < 36; slot++)
        player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
      j.sources.clear();
      j.sources.add(Sources.of(level.getBlockEntity(deep)));
      j.current = new ChunkPos(deep);
      j.slot = 0;
      j.paused = false;
      j.done = 0;
      j.total = 1;
      j.waiting.clear();
      j.seen.clear();
      j.level.getChunkSource().addRegionTicket(StructureJobs.TICKET, j.current, 0, j.nonce);
      j.tickets.put(j.current, level.getGameTime());
      StructureJobs.collect(player, j, Long.MAX_VALUE);
      h.assertTrue(
          j.paused
              && j.tickets.isEmpty()
              && ((BarrelBlockEntity) level.getBlockEntity(deep)).getItem(0).getCount() == 8,
          "full target pauses without loss or ticket leak");
      for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
      j.paused = false;
      for (int tries = 0; tries < 1000 && !j.paused; tries++) {
        j.lastRequest = -100000;
        StructureJobs.collect(player, j, Long.MAX_VALUE);
      }
      h.assertTrue(
          j.done == 1 && ((BarrelBlockEntity) level.getBlockEntity(deep)).getItem(0).isEmpty(),
          "paused target resumes remaining source");
      var pending = deep.offset(2, 0, 0);
      level.setBlockAndUpdate(pending, Blocks.BARREL.defaultBlockState());
      var pendingBe = (BarrelBlockEntity) level.getBlockEntity(pending);
      pendingBe.setItem(0, new ItemStack(Items.GOLD_INGOT));
      var stored = pendingBe.saveWithFullMetadata();
      var pendingChunk = level.getChunkAt(pending);
      pendingChunk.removeBlockEntity(pending);
      pendingChunk.setBlockEntityNbt(stored);
      h.assertTrue(
          pendingChunk.getBlockEntitiesPos().contains(pending),
          "pending stored BE position enumerated");
      h.assertTrue(
          Sources.inventory(player, pendingChunk.getBlockEntity(pending))
              .getStackInSlot(0)
              .is(Items.GOLD_INGOT),
          "stored unopened container resolves");
      var authority = new net.minecraft.nbt.CompoundTag();
      authority.putInt("Version", 1);
      authority.putString("State", "FOUND");
      authority.putString("Dimension", level.dimension().location().toString());
      authority.putString("Structure", "minecraft:" + name);
      authority.putUUID("Revision", UUID.randomUUID());
      authority.putInt("StartX", at.x);
      authority.putInt("StartZ", at.z);
      player.getPersistentData().put("ExplorerStructureSnapshotV1", authority);
      j.revision = authority.getUUID("Revision");
      h.assertTrue(StructureJobs.valid(player, j), "authoritative API accepts matching identity");
      authority.putUUID("Revision", UUID.randomUUID());
      h.assertTrue(!StructureJobs.valid(player, j), "new search invalidates old confirmation");
      authority.putString("State", "NON_STRUCTURE");
      h.assertTrue(!StructureJobs.valid(player, j), "biome/feature must not reuse old building");
      var cp = new ChunkPos(deep);
      j.level.getChunkSource().addRegionTicket(StructureJobs.TICKET, cp, 0, j.nonce);
      j.tickets.put(cp, level.getGameTime());
      StructureJobs.JOBS.put(player.getUUID(), j);
      StructureJobs.cancel(player.getUUID());
      h.assertTrue(
          j.tickets.isEmpty() && !StructureJobs.JOBS.containsKey(player.getUUID()),
          "cancel releases tickets");
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info(
              "NATURAL {} start={} pieces={} containers={} bounds={}",
              name,
              at,
              start.getPieces().size(),
              containers,
              j.region);
    }
    h.succeed();
  }
}
