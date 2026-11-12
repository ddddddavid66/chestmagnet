package dev.rsqa124;

public final class BenchCounters {
  private static Thread thread;
  static int candidates, probes;

  static void start() { thread = Thread.currentThread(); }
  static void stop() { thread = null; }

  public static void candidate() {
    if (thread == Thread.currentThread()) candidates++;
  }

  public static void probe() {
    if (thread == Thread.currentThread()) probes++;
  }
}
