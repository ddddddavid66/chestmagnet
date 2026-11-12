package dev.chestmagnet;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;

public final class LootrBridge {
  private static volatile boolean menuHook;

  public static void menuHookApplied() {
    menuHook = true;
  }

  static boolean hasMenuHook() {
    return menuHook;
  }
  // 只在一次磁铁调用期间捕获菜单；不得影响手动开箱或其他玩家。
  public static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();

  public static class Capture {
    public final Player player;
    public Container inventory;

    Capture(Player p) {
      player = p;
    }
  }

  public static Container capture(Player p, Runnable action) {
    var old = ACTIVE.get();
    var c = new Capture(p);
    ACTIVE.set(c);
    try {
      action.run();
      return c.inventory;
    } finally {
      if (old == null) ACTIVE.remove();
      else ACTIVE.set(old);
    }
  }
}
