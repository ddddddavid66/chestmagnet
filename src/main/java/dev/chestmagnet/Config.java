package dev.chestmagnet;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.IntValue RADIUS,
      EXCLUSIONS,
      SCAN,
      PROBES,
      STACKS,
      BUDGET,
      STRUCTURE_MARGIN,
      STRUCTURE_CHUNKS;

  static {
    var b = new ModConfigSpec.Builder();
    RADIUS = b.defineInRange("maxRadius", 64, 0, 128);
    EXCLUSIONS = b.defineInRange("maxExclusions", 256, 1, 1024);
    SCAN = b.defineInRange("scanPerPlayerPerTick", 128, 1, 4096);
    PROBES = b.defineInRange("slotProbesPerTick", 64, 1, 4096);
    STACKS = b.defineInRange("stacksPerTick", 8, 1, 128);
    BUDGET = b.defineInRange("globalBudgetMicros", 2000, 100, 20000);
    STRUCTURE_MARGIN = b.defineInRange("structureMargin", 16, 0, 64);
    STRUCTURE_CHUNKS = b.defineInRange("structureChunkLimit", 256, 1, 1024);
    SPEC = b.build();
  }
}
