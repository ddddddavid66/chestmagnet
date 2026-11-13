package dev.chestmagnet;

import java.util.UUID;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

public class MagnetMenu extends AbstractContainerMenu {
  public final InteractionHandSlot handSlot;
  public final UUID identity;

  public record InteractionHandSlot(boolean offhand) {}

  private final Player owner;
  private long recentTick = -20;
  private int recentRequests;

  boolean allowRecent(long tick) {
    if (tick - recentTick >= 20) { recentTick = tick; recentRequests = 0; }
    return ++recentRequests <= 12;
  }

  public MagnetMenu(int id, Inventory inv, boolean offhand, UUID identity) {
    super(Net.MENU.get(), id);
    owner = inv.player;
    handSlot = new InteractionHandSlot(offhand);
    this.identity = identity;
  }

  public ItemStack stack() {
    return handSlot.offhand ? owner.getOffhandItem() : owner.getMainHandItem();
  }

  @Override
  public boolean stillValid(Player p) {
    return p == owner && stack().is(ChestMagnet.MAGNET.get()) && Data.id(stack()).equals(identity);
  }

  @Override
  public ItemStack quickMoveStack(Player p, int index) {
    return ItemStack.EMPTY;
  }
}
