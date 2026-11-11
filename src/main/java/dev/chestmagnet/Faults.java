package dev.chestmagnet;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 来源级故障日志；隔离批次绝不自动重放。记录随磁铁保存。 */
public final class Faults {
  public record Report(UUID id, UUID magnet, String text) {}

  record Context(ServerPlayer player, ItemStack magnet, Sources.Ref ref, String stage) {}

  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<String> STRUCTURE = new ThreadLocal<>();

  private static String label(Context c) {
    if (c == null || c.ref == null) return "未归属具体来源";
    try {
      return c.ref.label(c.player);
    } catch (Exception ignored) {
      return c.ref.toString();
    }
  }

  static Context current() {
    return CURRENT.get();
  }

  static void context(ServerPlayer p, ItemStack m, Sources.Ref r, String stage) {
    CURRENT.set(new Context(p, m, r, stage));
  }

  static void stage(String stage) {
    var c = CURRENT.get();
    if (c != null) context(c.player, c.magnet, c.ref, stage);
  }

  static void structure(String key) {
    STRUCTURE.set(key);
  }

  static void clearContext() {
    CURRENT.remove();
    STRUCTURE.remove();
  }

  static boolean skipped(ServerPlayer p, ItemStack m, Sources.Ref r) {
    var list = Data.tag(m).getList("FaultSkipped", 10);
    String key = r.key(p);
    for (var e : list) {
      var t = (CompoundTag) e;
      if (t.getString("Key").equals(key)) return true;
      if (r.entity() == null)
        for (var at : r.members(p))
          if (t.getLongArray("Members").length > 0)
            for (long pos : t.getLongArray("Members"))
              if (pos == at.asLong()
                  && t.getString("Dimension").equals(p.level().dimension().location().toString()))
                return true;
    }
    return false;
  }

  static int skippedCount(ItemStack m) {
    return Data.tag(m).getList("FaultSkipped", 10).size();
  }

  static void clearSkip(ItemStack m, int index) {
    var l = Data.tag(m).getList("FaultSkipped", 10);
    if (index >= 0 && index < l.size()) l.remove(index);
    Data.tag(m).put("FaultSkipped", l);
  }

  static int count(ItemStack m, String key) {
    return Data.tag(m).getCompound("FaultCounts").getInt(key);
  }

  static void quarantine(ItemStack m, String key, String why) {
    var t = Data.tag(m);
    if (!t.contains(key)) return;
    var q = t.getList("Quarantined", 10);
    var entry = new CompoundTag();
    entry.putUUID("Id", UUID.randomUUID());
    entry.putString("Reason", why);
    entry.put("Payload", t.get(key).copy());
    entry.putString("OriginalKey", key);
    q.add(entry);
    t.put("Quarantined", q);
    t.remove(key);
  }

  static void migrate(ServerPlayer p, ItemStack m) {
    var t = Data.tag(m);
    boolean old = t.getBoolean("PendingUncertain") || t.getBoolean("RecoveryUncertain");
    if (!old) return;
    quarantine(m, "PendingLoot", "旧版缺少结算上下文，封存而不自动重放");
    quarantine(m, "Recovery", "旧版缺少结算上下文，封存而不自动重放");
    t.remove("PendingUncertain");
    t.remove("RecoveryUncertain");
    t.putBoolean("Enabled", false);
    context(p, m, null, "旧版缓存迁移");
    report(p, m, new IllegalStateException("旧版不确定缓存已封存；未补发，可再次开启继续其他来源"));
    clearContext();
  }

  static void report(ServerPlayer p, ItemStack m, Throwable ex) {
    var c = CURRENT.get();
    var id = UUID.randomUUID();
    var t = Data.tag(m);
    if ((c == null || c.ref == null)
        && t.contains("PendingOrigin")
        && t.getCompound("PendingOrigin")
            .getString("Dimension")
            .equals(p.level().dimension().location().toString())) {
      var r =
          new Sources.Ref(
              net.minecraft.core.BlockPos.of(t.getCompound("PendingOrigin").getLong("Position")),
              null);
      c = new Context(p, m, r, "接收已生成产物");
    }
    if (c != null && c.ref != null && !skipped(p, m, c.ref)) {
      var list = t.getList("FaultSkipped", 10);
      var f = new CompoundTag();
      f.putString("Key", c.ref.key(p));
      f.putString("Label", label(c));
      f.putString("Dimension", p.level().dimension().location().toString());
      f.putLongArray("Members", c.ref.members(p).stream().mapToLong(a -> a.asLong()).toArray());
      f.putUUID("Report", id);
      list.add(f);
      t.put("FaultSkipped", list);
    }
    // 实际接收/回退中断的栈不能再自动投递；其他已确定产物继续保留。
    if (t.getBoolean("RecoveryUncertain")) quarantine(m, "Recovery", "实际提取、接收或回退结果不确定");
    t.remove("RecoveryUncertain");
    quarantine(m, "OperationEvidence", "提取操作中断前证据，仅供诊断，不是补发凭证");
    String key = STRUCTURE.get();
    if (key != null) {
      var counts = t.getCompound("FaultCounts");
      int n = counts.getInt(key) + 1;
      counts.putInt(key, n);
      t.put("FaultCounts", counts);
      if (n >= 4)
        p.sendSystemMessage(
            Component.literal("当前结构已多次出错，建议放弃在此结构使用磁铁；仍可继续并跳过错误来源").withStyle(ChatFormatting.RED));
    }
    StringWriter sw = new StringWriter();
    ex.printStackTrace(new PrintWriter(sw));
    String text =
        "宝箱磁铁 1.2.1\n服务端日志：logs/chestmagnet/errors/"
            + java.time.LocalDate.now()
            + "/"
            + id
            + ".log\n报告："
            + id
            + "\n时间："
            + java.time.OffsetDateTime.now()
            + "\n维度："
            + p.level().dimension().location()
            + "\n来源："
            + label(c)
            + "\n阶段："
            + (c == null ? "任务" : c.stage)
            + "\n结构："
            + key
            + "\n措施：暂停本次；故障来源长期跳过。不确定批次封存且不补发。\n"
            + sw;
    try {
      var dir = Path.of("logs", "chestmagnet", "errors", java.time.LocalDate.now().toString());
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(id + ".log"), text);
    } catch (IOException ioe) {
      org.slf4j.LoggerFactory.getLogger("chestmagnet").error("Cannot save error report", ioe);
    }
    org.slf4j.LoggerFactory.getLogger("chestmagnet").error(text);
    if (p.connection != null)
      Net.sendReport(
          p,
          new Report(
              id,
              Data.id(m),
              text.length() > 22000 ? text.substring(0, 22000) + "\n（截断，完整报告在服务端）" : text));
    p.sendSystemMessage(
        Component.literal("【糟糕！出现了错误】")
            .withStyle(
                s ->
                    s.withColor(ChatFormatting.RED)
                        .withUnderlined(true)
                        .withClickEvent(
                            new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, "/chestmagnet_error " + id))));
    p.getInventory().setChanged();
  }
}
