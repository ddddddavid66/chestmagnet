package dev.chestmagnet;

import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;

/** Scope of a single native Lootr action. Manual opening never installs a scope. */
public final class LootrOpenScope {
  private static final ThreadLocal<LootrOpenScope> ACTIVE = new ThreadLocal<>();
  private static volatile boolean installed;

  private final ServerPlayer player;
  private MenuProvider captured;

  private LootrOpenScope(ServerPlayer player) { this.player = player; }

  public static void installed() { installed = true; }

  static MenuProvider openWithoutMenu(ServerPlayer player, Runnable action) {
    if (!installed || ACTIVE.get() != null) return null;
    var scope = new LootrOpenScope(player);
    ACTIVE.set(scope);
    try {
      action.run();
      return scope.captured;
    } finally {
      ACTIVE.remove();
    }
  }

  public static OptionalInt openMenu(ServerPlayer player, MenuProvider menu) {
    var scope = ACTIVE.get();
    if (scope != null && scope.player == player) {
      scope.captured = menu;
      return OptionalInt.empty();
    }
    return player.openMenu(menu);
  }
}
