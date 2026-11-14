package dev.chestmagnet;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/** 只读取功能槽原栈，保留终端扣能及 Curios 自身同步。 */
public final class CuriosCompat {
  private CuriosCompat() {}

  static boolean isAccessory(ServerPlayer player, ItemStack stack) {
    return stack.getItem() instanceof ICurioItem
        || CuriosApi.getCurio(stack).isPresent()
        || !CuriosApi.getItemStackSlots(stack, player).isEmpty()
        || !CuriosApi.getItemStackSlots(stack, player.level()).isEmpty();
  }

  static List<ItemStack> terminals(ServerPlayer player) {
    List<ItemStack> stacks = new ArrayList<>();
    CuriosApi.getCuriosInventory(player).ifPresent(inventory ->
        inventory.getCurios().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> {
              var slots = entry.getValue().getStacks();
              for (int slot = 0; slot < slots.getSlots(); slot++)
                stacks.add(slots.getStackInSlot(slot));
            }));
    return stacks;
  }
}
