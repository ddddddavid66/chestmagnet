package dev.chestmagnet.qa;

import dev.chestmagnet.ChestMagnet;
import dev.chestmagnet.CompassTargets;
import dev.chestmagnet.Data;
import dev.chestmagnet.StructureJobs;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "chestmagnetqa")
final class CompassProductionQa {
  private static final String CASE = System.getProperty("chestmagnet.qa", "");
  private static final Set<String> CASES = Set.of("compass", "edited", "enhance", "enhance-legacy");
  private static final ResourceLocation VILLAGE = ResourceLocation.withDefaultNamespace("village_plains");
  private static ServerPlayer p;
  private static ItemStack compass;
  private static BaseContainerBlockEntity barrel;
  private static ItemStack magnet;
  private static int ticks, stage;
  private static boolean done;
  private static UUID revision;

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post e) {
    if (done || !CASES.contains(CASE) || ++ticks < 12) return;
    try {
      run(e.getServer());
    } catch (Throwable ex) {
      done = true;
      NeoForgeProductionQa.LOG.error("QA_NEOFORGE_FAILURE {} stage={} snapshot={}", CASE, stage,
          p == null ? "-" : CompassTargets.snapshot(p), ex);
      cleanup(e.getServer());
    }
  }

  private static void run(MinecraftServer server) throws Exception {
    if (ticks > 750) throw new IllegalStateException("Compass test timed out");
    var level = server.overworld();
    if (stage == 0) {
      var near = level.findNearestMapStructure(StructureTags.VILLAGE, level.getSharedSpawnPos(), 64, false);
      require(near != null, "natural village exists");
      var approach = new BlockPos(near.getX() + (CASE.equals("edited") ? 72 : 0), level.getSeaLevel() + 4, near.getZ());
      p = NeoForgeProductionQa.player(level, "CompassNativeQA", approach);
      online(server).put(p.getUUID(), p);
      compass = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
          ResourceLocation.parse("explorerscompass:explorerscompass")));
      require(!compass.isEmpty(), "registered compass");
      p.getInventory().setItem(0, compass);
      if (CASE.startsWith("enhance")) {
        var general = Class.forName("com.chaosthedude.explorerscompass.config.ConfigHandler").getField("GENERAL").get(null);
        ((ModConfigSpec.BooleanValue)general.getClass().getField("asyncStructureSearch").get(general))
            .set(!Boolean.getBoolean("chestmagnet.qa.compassSync"));
        var fields = general.getClass().getFields();
        for (var field : fields)
          if (field.getName().equals("searchRequestCooldownMillis")) ((ModConfigSpec.IntValue)field.get(general)).set(0);
      }
      search("STRUCTURE", VILLAGE);
      var mark = CompassTargets.snapshot(p);
      require(mark.getString("State").equals("SEARCHING") || mark.getString("State").equals("FOUND"),
          "native search published launch state");
      revision = mark.getUUID("Revision");
      stage = 1;
      return;
    }
    var result = CompassTargets.snapshot(p);
    if (stage == 1 && result.getString("State").equals("FOUND")) {
      require(result.getUUID("Revision").equals(revision) && result.getString("Dimension").equals("minecraft:overworld"),
          "exact latest revision and source dimension");
      var chunk = level.getChunk(result.getInt("StartX"), result.getInt("StartZ"));
      var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
          .get(ResourceLocation.parse(result.getString("Structure")));
      var start = chunk.getStartForStructure(structure);
      require(start != null && start.isValid() && start.getChunkPos().equals(chunk.getPos()), "exact actual start");
      var center = start.getPieces().getFirst().getBoundingBox().getCenter();
      p.moveTo(center.getX() + .5, center.getY() + 2, center.getZ() + .5, 0, 0);
      // Probe a deep known container, not just the original height of the structure.
      var at = center.atY(level.getMinBuildHeight() + 8);
      level.getChunk(at);
      barrel = NeoForgeProductionQa.container(level, at, Blocks.BARREL);
      barrel.setItem(0, new ItemStack(Items.NETHER_STAR, 3));
      magnet = new ItemStack(ChestMagnet.MAGNET.get());
      p.getInventory().setItem(1, magnet);
      Data.edit(magnet, t -> t.putBoolean("AutoStructure", true));
      Method startJob = StructureJobs.class.getDeclaredMethod("beginAutomatic", ServerPlayer.class, ItemStack.class, CompoundTag.class);
      startJob.setAccessible(true);
      require((boolean)startJob.invoke(null, p, magnet, result), "native result starts production job");
      NeoForgeProductionQa.LOG.info("QA_NEOFORGE_COMPASS_START {} {} start={} deepY={}", CASE,
          result.getString("Structure"), start.getChunkPos(), at.getY());
      stage = 2;
    }
    if (stage != 2) return;
    // The QA player has no transport; the UUID registration is sufficient for server-authoritative jobs.
    StructureJobs.tick(server, System.nanoTime() + 2_000_000);
    if (p.getInventory().countItem(Items.NETHER_STAR) != 3) return;
    require(barrel.isEmpty(), "deep source received exactly once");
    if (CASE.startsWith("enhance")) {
      search("STRUCTURE", VILLAGE);
      var newResult = CompassTargets.snapshot(p);
      require(newResult.hasUUID("Revision") && !newResult.getUUID("Revision").equals(revision), "re-search invalidates previous revision");
      compass.getItem().getClass().getMethod("cancelSearch", Level.class, Player.class, ItemStack.class)
          .invoke(compass.getItem(), level, p, compass);
      require(CompassTargets.snapshot(p).getString("State").equals("CANCELLED"), "native cancel invalidates result");
    } else {
      compass.getItem().getClass().getMethod("setInactive", ItemStack.class, Player.class)
          .invoke(compass.getItem(), compass, p);
      require(CompassTargets.snapshot(p).getString("State").equals("CANCELLED"), "native cancel invalidates result");
    }
    done = true;
    cleanup(server);
    NeoForgeProductionQa.LOG.info("QA_NEOFORGE_PASS {} exact target, deep receipt and cancel", CASE);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void search(String kind, ResourceLocation requested) throws Exception {
    var item = compass.getItem();
    if (CASE.startsWith("enhance")) {
      var target = Class.forName("com.chaosthedude.explorerscompass.util.SearchTarget");
      item.getClass().getMethod("searchForTargets", Level.class, Player.class, target, List.class, BlockPos.class, ItemStack.class)
          .invoke(item, p.serverLevel(), p, Enum.valueOf((Class)target, kind), List.of(requested), p.blockPosition(), compass);
    } else if (CASE.equals("edited")) {
      item.getClass().getMethod("searchForStructure", Level.class, Player.class,
          ResourceLocation.class, List.class, BlockPos.class, ItemStack.class)
          .invoke(item, p.serverLevel(), p, requested, List.of(requested), p.blockPosition(), compass);
    } else {
      item.getClass().getMethod("searchForStructure", Level.class, Player.class,
          ResourceLocation.class, List.class, BlockPos.class, ItemStack.class, boolean.class)
          .invoke(item, p.serverLevel(), p, requested, List.of(requested), p.blockPosition(), compass, false);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<UUID, ServerPlayer> online(MinecraftServer server) throws ReflectiveOperationException {
    var field = PlayerList.class.getDeclaredField("playersByUUID");
    field.setAccessible(true);
    return (Map<UUID, ServerPlayer>)field.get(server.getPlayerList());
  }

  private static void cleanup(MinecraftServer server) {
    if (p == null) return;
    StructureJobs.cancel(p.getUUID());
    try { online(server).remove(p.getUUID()); }
    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
