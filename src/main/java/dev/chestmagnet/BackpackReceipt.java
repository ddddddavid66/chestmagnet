package dev.chestmagnet;

import java.util.*;
import java.util.function.IntFunction;
import net.minecraft.world.item.ItemStack;

/** 只包围本次磁铁实际投递；兼容钩子只提供证据，不修改升级结果。 */
public final class BackpackReceipt {
  private static final ThreadLocal<BackpackReceipt> ACTIVE = new ThreadLocal<>();
  private final ItemStack variant;
  private final Map<Object, Map<Integer, Slot>> touched = new IdentityHashMap<>();
  private final Set<Object> inputHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
  private int destroyed;
  private boolean verificationFailed;
  private Object wrapper;
  private record Slot(int before, IntFunction<ItemStack> read) {}

  BackpackReceipt(ItemStack stack) { variant = stack.copyWithCount(1); }

  void open() { ACTIVE.set(this); }
  void close() { ACTIVE.remove(); }

  static void bag(Object wrapper, Object handler) {
    var receipt = ACTIVE.get();
    if (receipt == null) return;
    receipt.wrapper = wrapper;
    receipt.inputHandlers.add(handler);
  }

  static void endBag() {
    var receipt = ACTIVE.get();
    if (receipt != null) receipt.wrapper = null;
  }

  public static boolean active() { return ACTIVE.get() != null; }

  public static void beforeSlot(Object handler, Object owner, int slot, ItemStack input,
      boolean simulate, IntFunction<ItemStack> read) {
    var receipt = ACTIVE.get();
    if (simulate || receipt == null || !receipt.owns(owner, handler) || !receipt.same(input)) return;
    receipt.observe(handler, slot, read, false);
  }

  public static void beforeChange(Object handler, Object owner, int slot, IntFunction<ItemStack> read) {
    var receipt = ACTIVE.get();
    if (receipt != null && receipt.owns(owner, handler)) receipt.observe(handler, slot, read, true);
  }

  private void observe(Object handler, int slot, IntFunction<ItemStack> read, boolean existingOnly) {
    try {
      var slots = touched.get(handler);
      if (slots != null && slots.containsKey(slot)) return;
      int count = amount(read.apply(slot));
      if (!existingOnly || count > 0)
        touched.computeIfAbsent(handler, h -> new HashMap<>()).put(slot, new Slot(count, read));
    } catch (RuntimeException | LinkageError ignored) { verificationFailed = true; }
  }

  public static int beforeVoid(Object owner, ItemStack input, boolean simulate) {
    var receipt = ACTIVE.get();
    return !simulate && receipt != null && receipt.owns(owner, null) && receipt.same(input)
        ? input.getCount() : 0;
  }

  public static void afterVoid(int before, ItemStack result) {
    var receipt = ACTIVE.get();
    if (receipt != null && before > 0 && (result.isEmpty() || receipt.same(result)))
      receipt.destroyed += Math.max(0, before - result.getCount());
  }

  private boolean owns(Object owner, Object handler) {
    return wrapper != null && (wrapper == owner || (handler != null && inputHandlers.contains(handler)));
  }

  private boolean same(ItemStack stack) { return !stack.isEmpty() && ItemStack.isSameItemSameTags(stack, variant); }
  private int amount(ItemStack stack) { return same(stack) ? stack.getCount() : 0; }

  int[] outcome(int accepted) {
    long stored = 0;
    for (var slots : touched.values())
      for (var entry : slots.entrySet()) {
        var slot = entry.getValue();
        stored += amount(slot.read().apply(entry.getKey())) - slot.before();
      }
    stored = Math.max(0, stored);
    // 槽位或销毁证据异常时，不从余量反推第二种结果。
    return verificationFailed || stored + destroyed > accepted ? new int[2] : new int[] {(int) stored, destroyed};
  }
}
