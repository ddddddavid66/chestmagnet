package dev.chestmagnet;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 限定来源的同步捕获；确定产物与不确定接收批次分开保存。 */
public final class LootCapture {
  private record Scope(ItemStack magnet, ServerPlayer player, Set<BlockPos> positions) {}

  private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

  public static ItemStack tool() {
    return ACTIVE.get() == null ? ItemStack.EMPTY : ACTIVE.get().magnet;
  }

  public static BlockPos sourcePos() {
    var s = ACTIVE.get();
    return s == null || s.positions.isEmpty() ? BlockPos.ZERO : s.positions.iterator().next();
  }

  public static boolean accepts(Player p, ResourceLocation table) {
    var s = ACTIVE.get();
    return s != null
        && s.player == p
        && table.getNamespace().equals("tetra")
        && table.getPath().startsWith("forged/");
  }

  public static boolean add(Level level, BlockPos pos, ItemStack item) {
    var s = ACTIVE.get();
    return s != null
        && s.player != null
        && s.player.level() == level
        && s.positions.contains(pos)
        && add(item);
  }

  public static boolean add(ItemStack item) {
    var s = ACTIVE.get();
    if (s == null) return false;
    if (!item.isEmpty()) {
      var t = Data.tag(s.magnet);
      var l = t.getList("PendingLoot", 10);
      l.add(item.save(new CompoundTag()));
      t.put("PendingLoot", l);
      var origin = new CompoundTag();
      if (s.player != null) {
        origin.putLong("Position", sourcePos().asLong());
        origin.putString("Dimension", s.player.level().dimension().location().toString());
        t.put("PendingOrigin", origin);
      }
    }
    return true;
  }

  static <T> T run(ItemStack m, Supplier<T> action) {
    var c = Faults.current();
    return run(
        m,
        c == null ? null : c.player(),
        c == null || c.ref() == null ? List.of() : c.ref().members(c.player()),
        action);
  }

  static <T> T run(
      ItemStack m, ServerPlayer p, Collection<BlockPos> positions, Supplier<T> action) {
    if (ACTIVE.get() != null) throw new IllegalStateException("nested capture");
    ACTIVE.set(new Scope(m, p, new LinkedHashSet<>(positions)));
    try {
      return action.get();
    } finally {
      ACTIVE.remove();
    }
  }

  static boolean drain(ItemStack m, Destinations.Target target, long deadline) {
    var t = Data.tag(m);
    var list = t.getList("PendingLoot", 10);
    int n = 0;
    while (!list.isEmpty() && n++ < Config.STACKS.get() && System.nanoTime() < deadline) {
      var item = ItemStack.of(list.getCompound(0));
      try {
        if (target.insert(item, true).getCount() == item.getCount()) break;
      } catch (RuntimeException ex) {
        Faults.quarantine(m, "PendingLoot", "模拟接口异常；未调用实际投递，故障来源产物延后封存");
        throw ex;
      }
      Faults.stage("接收已生成产物");
      ItemStack left;
      try {
        left = target.insert(item, false);
      } catch (RuntimeException ex) {
        // 仅本次已尝试交付的栈不确定，其余确定产物仍可续收。
        var batch = new CompoundTag();
        batch.put("Stack", list.remove(0));
        t.put("UncertainBatch", batch);
        t.put("PendingLoot", list);
        Faults.quarantine(m, "UncertainBatch", "接收接口中断，不自动补发");
        Faults.quarantine(m, "PendingLoot", "同故障来源尚未投递产物封存，不影响其他来源");
        throw ex;
      }
      if (left.isEmpty()) list.remove(0);
      else list.set(0, left.save(new CompoundTag()));
      t.put("PendingLoot", list);
    }
    if (list.isEmpty()) {
      t.remove("PendingLoot");
      t.remove("PendingOrigin");
    }
    return list.isEmpty();
  }

  static boolean pending(ItemStack m) {
    return !Data.tag(m).getList("PendingLoot", 10).isEmpty();
  }
}
