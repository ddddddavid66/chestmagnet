package dev.chestmagnet;

import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** 只读当前持有量；不走 FTB 的扣物品、合成或拾取入口。 */
final class QuestCompat {
  private static final int[] MODES = {0, 2, 1, 3};
  private static final String[] MOD_IDS = {"minecraft", "sophisticatedbackpacks", "refinedstorage", "beyonddimensions"};

  private QuestCompat() {}

  static boolean step(ServerPlayer player, QuestDetection.Scan scan, long deadline) {
    return step(player, scan, null, deadline);
  }

  static boolean priorityStep(ServerPlayer player, QuestDetection.Scan scan,
      java.util.Set<net.minecraft.world.item.Item> received, long deadline) {
    return step(player, scan, received, deadline);
  }

  private static boolean step(ServerPlayer player, QuestDetection.Scan scan,
      java.util.Set<net.minecraft.world.item.Item> received, long deadline) {
    var file = ServerQuestFile.INSTANCE;
    var team = file == null ? null : TeamData.get(player);
    if (team == null || team.isLocked()) { scan.release(); return false; }
    var tasks = file.getSubmitTasks();
    while (scan.task < tasks.size() && System.nanoTime() < deadline) {
      var task = tasks.get(scan.task);
      if (!(task instanceof ItemTask item)
          || received != null && !received.contains(item.getItemStack().getItem())
          || !eligible(team, item)) {
        scan.advance();
        continue;
      }
      int oldTask = scan.task, oldDestination = scan.destination;
      file.withPlayerContext(player, () -> checkOwned(player, team, item, scan, deadline));
      // 未完成读取器的单次槽位额度已经耗尽，留待下 tick；完成的目的地继续推进。
      if (scan.task == oldTask && scan.destination == oldDestination) return true;
    }
    if (scan.task >= tasks.size()) scan.release();
    return scan.task < tasks.size();
  }

  private static boolean eligible(TeamData team, ItemTask item) {
    if (item.consumesResources() || item.isOnlyFromCrafting() || item.isTaskScreenOnly()
        || team.isCompleted(item) || !team.canStartTasks(item.getQuest())) return false;
    var quest = item.getQuest();
    if (!quest.getRequireSequentialTasks()) return true;
    for (var previous : quest.getTasksAsList()) {
      if (previous == item) return true;
      if (!team.isCompleted(previous)) return false;
    }
    return false;
  }

  private static Destinations.Target view(ServerPlayer player, int mode) {
    return mode == 1 ? RsCompat.questTarget(player) : Destinations.get(player, mode);
  }

  private static void checkOwned(ServerPlayer player, TeamData team, ItemTask item,
      QuestDetection.Scan scan, long deadline) {
    if (scan.current != item || scan.team != team || scan.sample != item.getItemStack()) {
      scan.release();
      scan.current = item;
      scan.team = team;
      scan.sample = item.getItemStack();
      scan.destination = 0;
      scan.match = QuestMatch.compile(item);
    }
    if (scan.match == null) { scan.advance(); return; }
    while (scan.destination < MODES.length
        && scan.destination != 0 && !ModList.get().isLoaded(MOD_IDS[scan.destination])) scan.destination++;
    if (scan.destination == MODES.length) { scan.advance(); return; }
    int mode = MODES[scan.destination];
    if (scan.reader == null) {
      scan.reader = mode == 0 ? new PlayerQuestStock(player) : mode == 2 ? new BackpackQuestStock(player)
          : view(player, mode).stock(scan.match.candidates());
      if (scan.reader == null) { scan.nextDestination(); return; }
    }
    if (scan.reader instanceof QuestStock.Indexed index && !index.valid()) {
      scan.nextDestination(); // 下一轮重新读，不让高频变动的一个仓库饿死其他仓库。
      return;
    }
    long limit = item.getMaxProgress();
    if (!scan.reader.step(scan.match, limit, deadline)) return;
    long amount = scan.reader.amount();
    if (amount > team.getProgress(item) && scan.reader.valid()
        && (!(scan.reader instanceof QuestStock.Indexed idx)
            || idx.identity() == view(player, mode).stockIdentity())
        && !QuestDetection.carried(player).isEmpty() && eligible(team, item)) {
      team.setProgress(item, amount);
    }
    scan.nextDestination();
  }
}
