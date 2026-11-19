package dev.rsqa124;

import java.util.HashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 操作正式指南针界面。服务器只设置创造模式起点、测试物品，绝不代替指南针传送到结果。 */
@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class FreshTreasureClient {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("fresh-treasure");
  private static final String ARM = System.getProperty("qa.freshTreasure", "");
  private static final boolean REOPEN = Boolean.getBoolean("qa.freshReopen");
  private static final String GUI = "com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen";
  private static final String CLIENT = "com.chaosthedude.explorerscompass.unified.client.";
  private static final ResourceLocation TREASURE = new ResourceLocation("minecraft", "buried_treasure");
  private static final int LIMIT = Integer.getInteger("qa.freshCount", 6);
  private static final int FIRST = Integer.getInteger("qa.freshFirst", 0);
  private static final HashSet<Long> VISITED = new HashSet<>();
  private static int stage, attempts, settled, wait, x, z, stockAtStart;
  private static boolean fallback, done;
  private static java.util.UUID previousSearch;
  private static volatile boolean positioned;
  private static volatile Throwable error;
  private static volatile String observation;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END || ARM.isEmpty() || done) return;
    var mc = Minecraft.getInstance();
    try {
      if (error != null) throw new IllegalStateException("server QA", error);
      if (REOPEN) {
        reopen(mc);
        return;
      }
      if (stage == 0 && mc.screen instanceof TitleScreen) {
        stage = 1;
        long seed = Long.getLong("qa.freshSeed", -3563551871153301544L);
        mc.createWorldOpenFlows().createFreshLevel("FreshBuriedQA",
            new LevelSettings("FreshBuriedQA", GameType.CREATIVE, false, Difficulty.NORMAL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT),
            new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions);
      }
      if (mc.player == null || mc.getSingleplayerServer() == null) return;
      step(mc);
    } catch (Throwable failure) {
      done = true;
      LOG.error("FRESH_FAILED arm={} stage={} attempt={}", FreshTreasureTrace.ARM, stage, attempts, failure);
      mc.stop();
    }
  }

  private static void reopen(Minecraft mc) {
    if (mc.player == null || mc.getSingleplayerServer() == null || mc.level == null) return;
    x = Integer.getInteger("qa.freshReopenX");
    z = Integer.getInteger("qa.freshReopenZ");
    if (!mc.level.hasChunk(x >> 4, z >> 4)) return;
    var server = mc.getSingleplayerServer();
    var id = mc.player.getUUID();
    if (stage == 0) {
      stage = 1;
      server.execute(() -> stockAtStart = server.getPlayerList().getPlayer(id).getInventory().items.stream()
          .mapToInt(ItemStack::getCount).sum());
      observe(mc, "reopen");
    } else if (stage == 1 && ++settled == 100) {
      observe(mc, "settled100");
      stage = 2;
    } else if (stage == 2 && observation != null) {
      LOG.info("FRESH_REOPEN_COMPLETE arm={} xz={},{} {}", FreshTreasureTrace.ARM, x, z, observation);
      done = true;
      mc.stop();
    }
  }

  private static void step(Minecraft mc) throws ReflectiveOperationException {
    if (stage == 1) {
      start(mc);
      return;
    }
    var playerAt = mc.player.blockPosition();
    int i = FIRST + attempts;
    boolean atOrigin = i == 0 || playerAt.getX() == 1536 * i && playerAt.getZ() == (i % 2 == 0 ? 768 : -768);
    if (stage == 2 && positioned && atOrigin && isCompass(mc.player.getMainHandItem())
        && token(mc.player.getMainHandItem()) == null
        && mc.level.hasChunk(mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4)) {
      mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
      stage = 3;
    }
    if (stage == 3 && isGui(mc)) {
      if (!Boolean.TRUE.equals(Class.forName(CLIENT + "ClientSession").getMethod("ready").invoke(null))) return;
      previousSearch = token(mc.player.getMainHandItem());
      mc.screen.getClass().getMethod("searchForStructure", ResourceLocation.class).invoke(mc.screen, TREASURE);
      stage = 4;
      wait = 0;
      return;
    }
    if (stage == 4) {
      var tag = mc.player.getMainHandItem().getTag();
      var resultToken = token(mc.player.getMainHandItem());
      if (tag == null || !tag.getString("ECFiltersSearchStage").equals("found") || resultToken == null
          || resultToken.equals(previousSearch) || !tag.getString("StructureKey").equals(TREASURE.toString())) {
        if (++wait > 1800) finish(mc, "search_timeout", "-");
        return;
      }
      x = tag.getInt("FoundX");
      z = tag.getInt("FoundZ");
      long key = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
      if (!VISITED.add(key)) {
        finish(mc, "repeated_start", "-");
        return;
      }
      LOG.info("FRESH_SEARCH arm={} attempt={} xz={},{} start={},{} compass={}", FreshTreasureTrace.ARM,
          FIRST + attempts, x, z, x >> 4, z >> 4, tag);
      if (!isGui(mc)) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
      observe(mc, "before_click");
      stage = 5;
      wait = 0;
    }
    if (stage == 5) {
      if (clickTeleport(mc)) {
        stage = 6;
        wait = 0;
        fallback = false;
      } else if (++wait > 400) finish(mc, "button_unavailable", "-");
      return;
    }
    if (stage == 6) {
      Object result = Class.forName(CLIENT + "LandingClientState").getMethod("state", ItemStack.class)
          .invoke(null, mc.player.getMainHandItem());
      String state = String.valueOf(result);
      String reason = String.valueOf(Class.forName(CLIENT + "LandingClientState")
          .getMethod("preparing", ItemStack.class).invoke(null, mc.player.getMainHandItem()));
      if (state.equals("SUCCEEDED") && mc.player.blockPosition().getX() == x && mc.player.blockPosition().getZ() == z) {
        stage = 7;
        settled = 0;
        LOG.info("FRESH_LANDING arm={} attempt={} at={}", FreshTreasureTrace.ARM, FIRST + attempts,
            mc.player.blockPosition().toShortString());
        observe(mc, "landed");
      } else if (state.equals("FAILED")
          || state.equals("FAILED_RETRYABLE") && reason.contains("treasure_chest_missing")
          || state.equals("CANCELLED") && reason.contains("expired") || ++wait > 1000) {
        LOG.info("FRESH_TELEPORT_FAILURE attempt={} state={} reason={}", FIRST + attempts, state, reason);
        observe(mc, "teleport_failure");
        stage = 8;
        wait = 0;
      } else if (!fallback && (state.equals("NEEDS_CONFIRMATION") || state.equals("FAILED_RETRYABLE"))) {
        fallback = clickTeleport(mc);
      }
      return;
    }
    if (stage == 7) {
      if (++settled == 20) observe(mc, "settled20");
      if (settled == 100) observe(mc, "settled100");
      if (settled >= 101 && observation != null) finish(mc, "landed", observation);
      return;
    }
    if (stage == 8 && (observation != null || ++wait > 100)) finish(mc, "teleport_failed", observation);
  }

  private static boolean isCompass(ItemStack stack) {
    var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
    return id != null && id.toString().equals("explorerscompass:explorerscompass");
  }

  private static java.util.UUID token(ItemStack stack) {
    var tag = stack.getTag();
    return tag != null && tag.hasUUID("ECFiltersSearchToken") ? tag.getUUID("ECFiltersSearchToken") : null;
  }

  private static boolean isGui(Minecraft mc) { return mc.screen != null && mc.screen.getClass().getName().equals(GUI); }

  private static boolean clickTeleport(Minecraft mc) throws ReflectiveOperationException {
    if (!isGui(mc)) return false;
    var field = mc.screen.getClass().getDeclaredField("teleportButton");
    field.setAccessible(true);
    var button = (Button)field.get(mc.screen);
    return button.visible && button.active && mc.screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0);
  }

  private static void start(Minecraft mc) {
    mc.setScreen(null);
    positioned = false;
    observation = null;
    var server = mc.getSingleplayerServer();
    var id = mc.player.getUUID();
    int i = FIRST + attempts;
    server.execute(() -> {
      try {
        var p = server.getPlayerList().getPlayer(id);
        if (p == null || !p.isCreative()) throw new IllegalStateException("QA must use new creative world");
        p.getInventory().clearContent();
        var compass = ForgeRegistries.ITEMS.getValue(new ResourceLocation("explorerscompass", "explorerscompass"));
        if (compass == null) throw new IllegalStateException("native compass missing");
        p.getInventory().setItem(0, new ItemStack(compass));
        p.getInventory().selected = 0;
        if (!FreshTreasureTrace.ARM.equals("none")) {
          var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet", "chest_magnet"));
          if (item == null) throw new IllegalStateException("test magnet missing");
          var magnet = new ItemStack(item);
          var settings = magnet.getOrCreateTagElement("ChestMagnet");
          settings.putBoolean("Enabled", !FreshTreasureTrace.ARM.equals("off"));
          settings.putBoolean("AutoStructure", FreshTreasureTrace.ARM.equals("structure"));
          settings.putBoolean("QuestDetection", false);
          settings.putInt("Y", FreshTreasureTrace.ARM.equals("structure") ? 0 : 24);
          p.getInventory().setItem(8, magnet);
        }
        stockAtStart = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
        // 固定搜索起点；决不使用测试传送作为到达目标的结果。
        if (i != 0) p.teleportTo(server.overworld(), 1536 * i + .5, 110, (i % 2 == 0 ? 768 : -768) + .5, 0, 0);
        LOG.info("FRESH_ORIGIN arm={} attempt={} at={} creative={}", FreshTreasureTrace.ARM, i,
            p.blockPosition().toShortString(), p.isCreative());
        positioned = true;
      } catch (Throwable failure) { error = failure; }
    });
    stage = 2;
  }

  private static void observe(Minecraft mc, String phase) {
    var server = mc.getSingleplayerServer();
    var id = mc.player.getUUID();
    int cx = x, cz = z, attempt = FIRST + attempts;
    String client = clientBlocks(mc, cx, cz);
    server.execute(() -> {
      try {
        var p = server.getPlayerList().getPlayer(id);
        var lev = p.serverLevel();
        int stored = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum() - stockAtStart;
        var summary = FreshTreasureTrace.sample(lev, cx, cz);
        LOG.info("FRESH_SAMPLE arm={} attempt={} phase={} tick={} received={} server={} client={}",
            FreshTreasureTrace.ARM, attempt, phase, lev.getGameTime(), stored, summary, client);
        if (phase.equals("settled100") || phase.equals("teleport_failure")) observation = summary;
      } catch (Throwable failure) { error = failure; }
    });
  }

  private static String clientBlocks(Minecraft mc, int x, int z) {
    if (!mc.level.hasChunk(x >> 4, z >> 4)) return "unknown";
    var result = new java.util.ArrayList<String>();
    var pos = new net.minecraft.core.BlockPos.MutableBlockPos(x, 0, z);
    for (int y = mc.level.getMinBuildHeight(); y < mc.level.getMaxBuildHeight(); y++) {
      var key = ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(pos.setY(y)).getBlock());
      if (key != null && key.getPath().contains("chest")) result.add(y + ":" + key);
    }
    return result.toString();
  }

  private static void finish(Minecraft mc, String result, String detail) {
    LOG.info("FRESH_RESULT arm={} attempt={} result={} xz={},{} {}", FreshTreasureTrace.ARM,
        FIRST + attempts, result, x, z, detail);
    if (++attempts < LIMIT) { start(mc); return; }
    done = true;
    LOG.info("FRESH_COMPLETE arm={} attempts={} unique={}", FreshTreasureTrace.ARM, attempts, VISITED.size());
    mc.stop();
  }
}
