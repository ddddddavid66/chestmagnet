package dev.rsqa124;

import static dev.rsqa124.Production128Repair.require;

import dev.chestmagnet.Destinations;
import dev.chestmagnet.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.data.DataStorage;

/** 用真实生成的两只远古城市箱分开验证转换竞争和已完成转换的情况。 */
final class AncientCityProbe {
  private static final ResourceLocation TABLE = new ResourceLocation("minecraft:chests/ancient_city");
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-ancient-qa");
  private static BlockPos early, live;
  private static BlockEntity liveBefore;
  private static int settleUntil;

  static boolean tick(ServerLevel level, boolean baseline, int ticks) throws Exception {
    if (early == null) {
      early = Production128Repair.locateChest(level, "minecraft:ancient_city", TABLE, null, null);
      live = Production128Repair.locateChest(level, "minecraft:ancient_city", TABLE, null, early);
      var chest = level.getBlockEntity(early);
      var player = Production128Repair.player(level, "AncientEarly");
      player.setPos(early.getX() + .5, early.getY(), early.getZ() + .5);
      int received = drain(player, chest);
      var remaining = chest.saveWithoutMetadata().getString("LootTable");
      require(baseline ? received > 0 && remaining.isEmpty() : received == 0 && TABLE.toString().equals(remaining),
          "Unexpected before-conversion behavior: received=" + received + " table=" + remaining);
      LOG.info("REPAIR128_ANCIENT_PENDING at={} block={} received={} remainingTable={}",
          early, blockId(level, early), received, remaining);
      forceNeighbors(level, early);
      forceNeighbors(level, live);
      return false;
    }
    if (liveBefore == null) {
      if (!(level.getBlockEntity(live) instanceof ILootBlockEntity)) {
        require(ticks < 900, "Lootr did not convert second Ancient City chest");
        return false;
      }
      return collectConverted(level, baseline, ticks);
    }
    if (ticks < settleUntil) return false;
    require(level.getBlockEntity(live) == liveBefore, "converted chest block entity changed after several ticks");
    require(baseline ? blockId(level, early).equals("minecraft:chest")
        : level.getBlockEntity(early) instanceof ILootBlockEntity, "first chest did not settle as expected");
    LOG.info("REPAIR128_ANCIENT_SETTLED early={} alreadyConverted={}", blockId(level, early), blockId(level, live));
    return true;
  }

  private static boolean collectConverted(ServerLevel level, boolean baseline, int ticks) throws Exception {
    liveBefore = level.getBlockEntity(live);
    var before = blockId(level, live);
    var id = ((ILootBlockEntity) liveBefore).getTileId();
    var player = Production128Repair.player(level, "AncientConverted");
    player.setPos(live.getX() + .5, live.getY(), live.getZ() + .5);
    // 即使 Lootr 配置允许衰减，磁铁也不应启动计时；发布版的旧入口会启动。
    ConfigManager.DECAY_ALL.set(true);
    int received = drain(player, liveBefore);
    require(received > 0 && player.getInventory().items.stream().mapToInt(ItemStack::getCount).sum() == received,
        "converted chest loot did not reach the destination");
    require(before.equals(blockId(level, live)) && level.getBlockEntity(live) == liveBefore
        && id.equals(((ILootBlockEntity) liveBefore).getTileId()), "converted chest was replaced during transfer");
    var decay = DataStorage.getDecayValue(id);
    require(baseline ? decay >= 0 : decay == -1, "unexpected decay timer: " + decay);
    ConfigManager.DECAY_ALL.set(false);
    LOG.info("REPAIR128_ANCIENT_CONVERTED at={} before={} after={} received={} decay={}",
        live, before, blockId(level, live), received, decay);
    settleUntil = ticks + 60;
    return false;
  }

  private static int drain(ServerPlayer player, BlockEntity chest) throws Exception {
    var magnet = Production128Repair.magnet();
    var source = Sources.inventory(player, chest, magnet);
    if (source == null) return 0;
    var target = Destinations.get(player, 0);
    var mover = Class.forName("dev.chestmagnet.Transfer").getDeclaredMethod("move",
        IItemHandler.class, int.class, int.class, Destinations.Target.class, ItemStack.class);
    mover.setAccessible(true);
    int moved = 0;
    for (int slot = 0; slot < source.getSlots(); slot++) moved += (int) mover.invoke(null, source, slot, 64, target, magnet);
    // 两种扫描最终共用这一步；确认收尾也不会把个人容器交给普通解锁逻辑。
    var finish = Class.forName("dev.chestmagnet.SourceExtras").getDeclaredMethod("finish",
        ServerPlayer.class, Object.class, IItemHandler.class, ItemStack.class);
    finish.setAccessible(true);
    require((boolean) finish.invoke(null, player, chest, source, magnet), "source did not finish empty");
    return moved;
  }

  private static void forceNeighbors(ServerLevel level, BlockPos pos) {
    var chunk = new ChunkPos(pos);
    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
      level.setChunkForced(chunk.x + dx, chunk.z + dz, true);
  }

  private static String blockId(ServerLevel level, BlockPos pos) {
    return String.valueOf(ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()));
  }
}
