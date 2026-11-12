package dev.rsqa124;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class NearbyClientChecks {
  private static boolean requested, finished;
  private static boolean gridAttempted;
  private static int ticks;
  private static long lastFrame;
  private static final ArrayList<Long> frames = new ArrayList<>();
  private static final java.util.Map<String, ArrayList<Long>> questFrames = new java.util.HashMap<>();

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (NearbyProductionChecks.SUITE.isEmpty() || event.phase != TickEvent.Phase.END) return;
    var mc = Minecraft.getInstance();
    if (mc.player == null && ++ticks % 200 == 0 && mc.screen != null)
      NearbyProductionChecks.LOG.info("QA125_WAIT {} {}", mc.screen.getClass().getName(), mc.screen.getTitle().getString());
    if (!requested && mc.screen instanceof TitleScreen) {
      requested = true;
      mc.createWorldOpenFlows().loadLevel(mc.screen, "RsQa124");
    }
    if (mc.player == null && mc.screen instanceof ConfirmScreen
        && mc.screen.getTitle().getString().equals(net.minecraft.network.chat.Component
            .translatable("selectWorld.experimental.title").getString()))
      mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
          .findFirst().ifPresent(Button::onPress);
    if (mc.player == null && mc.screen instanceof BackupConfirmScreen backup
        && backup.getTitle().getString().equals(net.minecraft.network.chat.Component
            .translatable("selectWorld.backupQuestion.experimental").getString())) {
      var proceed = net.minecraft.network.chat.Component
          .translatable("selectWorld.backupJoinSkipButton").getString();
      backup.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
          .filter(b -> b.getMessage().getString().equals(proceed))
          .findFirst().ifPresent(Button::onPress);
    }
    if ("rs125".equals(NearbyProductionChecks.SUITE) && mc.player != null) {
      if (Production125Rs.openGrid && !gridAttempted) {
        gridAttempted = true;
        mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
      }
      if (mc.screen instanceof com.refinedmods.refinedstorage.screen.grid.GridScreen screen
          && !screen.getView().getAllStacks().isEmpty()) Production125Rs.gridVerified = true;
    }
    if (!finished && NearbyProductionChecks.done) {
      finished = true;
      frames.sort(Long::compare);
      if (!frames.isEmpty())
        NearbyProductionChecks.LOG.info("QA125_FRAME n={} p50_us={} p95_us={}", frames.size(),
            frames.get(frames.size() / 2), frames.get((frames.size() - 1) * 95 / 100));
      for (var entry : questFrames.entrySet()) {
        var times = entry.getValue();
        times.sort(Long::compare);
        NearbyProductionChecks.LOG.info("QA126_FRAME phase={} n={} p50_us={} p95_us={}",
            entry.getKey(), times.size(), times.get(times.size() / 2), times.get((times.size() - 1) * 95 / 100));
      }
      mc.stop();
    }
  }

  @SubscribeEvent
  public static void frame(TickEvent.RenderTickEvent event) {
    if (event.phase != TickEvent.Phase.END) return;
    if ("ftb126".equals(NearbyProductionChecks.SUITE)) {
      long now = System.nanoTime();
      String phase = NearbyProductionChecks.questFramePhase;
      if (!phase.isEmpty() && lastFrame != 0)
        questFrames.computeIfAbsent(phase, unused -> new ArrayList<>()).add((now - lastFrame) / 1000);
      lastFrame = now;
      return;
    }
    if (!"bench".equals(NearbyProductionChecks.SUITE)) return;
    long now = System.nanoTime();
    if (NearbyProductionChecks.running && lastFrame != 0) frames.add((now - lastFrame) / 1000);
    lastFrame = now;
  }
}
