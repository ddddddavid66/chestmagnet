package dev.chestmagnet;

import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 每个切片重建访问视图；跨 tick 只保留游标与可验证的库存版本。 */
final class QuestStock {
  interface View {
    Object identity();
    Object revision();
    List<? extends Row> rows(Item item);
  }

  interface Row {
    ItemStack stack();
    long count();
  }

  interface Reader extends AutoCloseable {
    /** 返回 true 表示已有完整结果，或已达到任务所需数量。 */
    boolean step(QuestMatch match, long limit, long deadline);
    long amount();
    boolean valid();
    default void close() {}
  }

  static final class Indexed implements Reader {
    private final View view;
    private final Object version;
    private final AutoCloseable listener;
    private final List<Item> candidates;
    private int item, row;
    private long count;
    private List<? extends Row> snapshot;

    Indexed(View view, Set<Item> candidates, AutoCloseable listener) {
      this.view = view;
      this.listener = listener;
      version = view.revision();
      this.candidates = List.copyOf(candidates);
    }

    Object identity() { return view.identity(); }
    public boolean valid() { return java.util.Objects.equals(version, view.revision()); }
    public long amount() { return count; }

    public boolean step(QuestMatch match, long limit, long deadline) {
      int remaining = 64;
      while (item < candidates.size() && count < limit && remaining-- > 0
          && System.nanoTime() < deadline) {
        if (snapshot == null) snapshot = view.rows(candidates.get(item));
        if (row >= snapshot.size()) {
          snapshot = null;
          row = 0;
          item++;
          continue;
        }
        var value = snapshot.get(row++);
        long quantity = value.count();
        if (quantity > 0 && match.matches().test(value.stack()))
          count += Math.min(limit - count, quantity);
      }
      return item == candidates.size() || count >= limit;
    }

    public void close() {
      if (listener != null) try {
        listener.close();
      } catch (Exception e) {
        throw new IllegalStateException("无法释放任务库存监听", e);
      }
    }
  }

  private QuestStock() {}
}
