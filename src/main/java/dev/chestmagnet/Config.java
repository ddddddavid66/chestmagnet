package dev.chestmagnet;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
  public static final ForgeConfigSpec SPEC;
  public static final ForgeConfigSpec.IntValue RADIUS, EXCLUSIONS, SCAN, PROBES, STACKS, BUDGET;

  static {
    var b = new ForgeConfigSpec.Builder();
    RADIUS = b.defineInRange("maxRadius", 64, 0, 128);
    EXCLUSIONS = b.defineInRange("maxExclusions", 256, 1, 1024);
    SCAN = b.defineInRange("scanPerPlayerPerTick", 128, 1, 4096);
    PROBES = b.defineInRange("slotProbesPerTick", 64, 1, 4096);
    STACKS = b.defineInRange("stacksPerTick", 8, 1, 128);
    BUDGET = b.defineInRange("globalBudgetMicros", 2000, 100, 20000);
    SPEC = b.build();
  }
}
