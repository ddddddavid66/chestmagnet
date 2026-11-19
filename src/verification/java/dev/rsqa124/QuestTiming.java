package dev.rsqa124;

import java.util.ArrayList;
import java.util.List;

/** 独立 QA 模组测量真实检测时间，不随发行 JAR 分发。 */
public final class QuestTiming {
  private static final List<Long> samples = new ArrayList<>();
  public static boolean countReaders;
  public static int readers;
  static java.util.function.LongSupplier watchedProgress;
  static boolean measureOriginal;
  static long commits;
  private static long began, inkBefore;

  public static void begin() {
    if (watchedProgress != null) inkBefore = watchedProgress.getAsLong();
    began = measureOriginal || "on".equals(NearbyProductionChecks.questFramePhase) ? System.nanoTime() : 0;
  }

  public static void end() {
    // 用回调隔离测试夹具：整包没有超越维度时不加载 InkChainProbe 的类型。
    if (watchedProgress != null) {
      long after = watchedProgress.getAsLong();
      if (after > inkBefore) {
        commits++;
        NearbyProductionChecks.LOG.info("INK_MAGNET_COMMIT before={} after={}", inkBefore, after);
      }
    }
    if (began != 0) samples.add((System.nanoTime() - began) / 1000);
    began = 0;
  }

  public static void report() {
    if (samples.isEmpty()) return;
    samples.sort(Long::compare);
    NearbyProductionChecks.LOG.info("QA126_FTB_TIME n={} p50_us={} p95_us={} max_us={}", samples.size(),
        samples.get(samples.size() / 2), samples.get((samples.size() - 1) * 95 / 100),
        samples.get(samples.size() - 1));
  }

  private QuestTiming() {}
}
