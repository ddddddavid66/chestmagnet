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

  private int recentSequence = -1;
  private java.util.List<Tracking.Rule> offeredHistory = java.util.List.of();
  private int trackingTick = -20, trackingRequests;

  void offerHistory(int sequence, net.minecraft.nbt.ListTag rows) {
    recentSequence = sequence;
    var snapshot = new java.util.ArrayList<Tracking.Rule>(8);
    for (int i = 0; i < rows.size(); i++) snapshot.add(Tracking.fromHistory(rows.getCompound(i).getCompound("Display")));
    offeredHistory = snapshot;
  }

  Tracking.Rule historyRule(int sequence, int index) {
    return sequence == recentSequence && index >= 0 && index < offeredHistory.size()
        ? offeredHistory.get(index) : null;
  }

  boolean allowTracking(int tick) {
    if (tick - trackingTick >= 20) { trackingTick = tick; trackingRequests = 0; }
    return ++trackingRequests <= 16;
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
