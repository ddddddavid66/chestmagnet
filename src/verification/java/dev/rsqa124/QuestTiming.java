package dev.rsqa124;

import java.util.ArrayList;
import java.util.List;

/** 独立 QA 模组测量真实检测时间，不随发行 JAR 分发。 */
public final class QuestTiming {
  private static final List<Long> samples = new ArrayList<>();
  private static long began;

  public static void begin() {
    began = "on".equals(NearbyProductionChecks.questFramePhase) ? System.nanoTime() : 0;
  }

  public static void end() {
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
