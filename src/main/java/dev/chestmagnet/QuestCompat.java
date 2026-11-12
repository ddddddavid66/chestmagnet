package dev.chestmagnet;

import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** 只读当前持有量；不走 FTB 的扣物品、合成或拾取入口。 */
final class QuestCompat {
  private static final int[] MODES = {2, 1, 3};
  private static final String[] MOD_IDS = {"sophisticatedbackpacks", "refinedstorage", "beyonddimensions"};

  private QuestCompat() {}

  static boolean step(ServerPlayer player, QuestDetection.Scan scan, long deadline) {
    var file = ServerQuestFile.INSTANCE;
    if (file == null) return false;
    var team = TeamData.get(player);
    if (team == null || team.isLocked()) return false;
    var tasks = file.getSubmitTasks();
    int checked = 0;
    while (scan.task < tasks.size() && checked++ < 16 && System.nanoTime() < deadline) {
      var task = tasks.get(scan.task);
      if (task instanceof ItemTask item && !item.consumesResources() && !item.isOnlyFromCrafting()
          && !item.isTaskScreenOnly() && !team.isCompleted(item) && team.canStartTasks(item.getQuest())
          && sequenceAllows(team, item)) {
        while (scan.destination < MODES.length
            && !ModList.get().isLoaded(MOD_IDS[scan.destination])) scan.destination++;
        if (scan.destination < MODES.length) {
          int mode = MODES[scan.destination++];
          file.withPlayerContext(player, () -> checkOwned(player, team, item, mode, deadline));
          if (scan.destination == MODES.length) { scan.task++; scan.destination = 0; }
          // 单个目的地超时不会让排在后面的网络一直得不到时间片。
          return scan.task < tasks.size();
        }
      }
      scan.task++;
      scan.destination = 0;
    }
    return scan.task < tasks.size();
  }

  private static boolean sequenceAllows(TeamData team, ItemTask item) {
    var quest = item.getQuest();
    if (!quest.getRequireSequentialTasks()) return true;
    for (var previous : quest.getTasksAsList()) {
      if (previous == item) return true;
      if (!team.isCompleted(previous)) return false;
    }
    return false;
  }

  private static void checkOwned(ServerPlayer player, TeamData team, ItemTask item, int mode, long deadline) {
    var key = item.getItemStack();
    long limit = item.getMaxProgress(), known = team.getProgress(item);
    if (key.isEmpty() || limit <= known) return;
    // 三处可能通过其它模组桥接，只采可信的最高值，不跨网络累加。
    var view = mode == 1 ? RsCompat.questTarget(player) : Destinations.get(player, mode);
    long amount = view.count(key, item::test, limit, deadline);
    if (amount > known) {
      var magnet = Engine.active(player);
      if (!magnet.isEmpty() && Data.questDetection(Data.tag(magnet)))
        team.setProgress(item, amount);
    }
  }
}
