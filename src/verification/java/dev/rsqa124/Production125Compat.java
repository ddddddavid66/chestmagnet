package dev.rsqa124;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

final class Production125Compat {
  static void verify(ServerPlayer player) throws ReflectiveOperationException {
    var level = player.serverLevel();
    var pos = new BlockPos(0, 120, 2);
    var chest = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("lootr", "lootr_chest"));
    require(chest != null, "Lootr chest registered");
    level.setBlockAndUpdate(pos, chest.defaultBlockState());
    var source = (RandomizableContainerBlockEntity) level.getBlockEntity(pos);
    source.setLootTable(new ResourceLocation("minecraft", "chests/simple_dungeon"), 42L);
    var other = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "Qa125Other"));
    other.setPos(pos.getX(), pos.getY(), pos.getZ());
    var inventory = Class.forName("dev.chestmagnet.Sources")
        .getMethod("inventory", ServerPlayer.class, Object.class);
    var a = (IItemHandler) inventory.invoke(null, player, source);
    var b = (IItemHandler) inventory.invoke(null, other, source);
    require(a != null && b != null && player.containerMenu == player.inventoryMenu,
        "Lootr per-player inventory and no opened GUI");
    for (int i = 0; i < a.getSlots(); i++) a.extractItem(i, 64, false);
    a.insertItem(0, new ItemStack(Items.NETHER_STAR), false);
    for (int i = 0; i < b.getSlots(); i++)
      require(!b.getStackInSlot(i).is(Items.NETHER_STAR), "Lootr isolated player inventory");
    player.teleportTo(0.5, 120, 1.5);
    chest.use(chest.defaultBlockState(), level, pos, player, InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    require(player.containerMenu != player.inventoryMenu, "native manual Lootr menu still opens");
    player.closeContainer();

    var cart = new noobanidus.mods.lootr.entity.LootrChestMinecartEntity(level, 2.5, 120, 2.5);
    cart.setLootTable(new ResourceLocation("minecraft", "chests/abandoned_mineshaft"), 2L);
    level.addFreshEntity(cart);
    require(inventory.invoke(null, player, cart) != null && cart.isAlive()
        && player.containerMenu == player.inventoryMenu, "Lootr cart remains alive and menu closed");

    var pack = ForgeRegistries.ITEMS.getValue(new ResourceLocation("sophisticatedbackpacks", "backpack"));
    require(pack != null, "backpack registered");
    other.getInventory().clearContent();
    var first = new ItemStack(pack);
    var second = new ItemStack(pack);
    other.getInventory().setItem(0, first);
    other.getInventory().setItem(35, second);
    var cap = net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance();
    var slotsA = first.getCapability(cap).orElseThrow(IllegalStateException::new).getInventoryHandler();
    var slotsB = second.getCapability(cap).orElseThrow(IllegalStateException::new).getInventoryHandler();
    for (int i = 0; i < slotsA.getSlots(); i++) slotsA.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
    slotsA.setStackInSlot(0, new ItemStack(Items.DIAMOND, 61));
    var target = Class.forName("dev.chestmagnet.Destinations").getMethod("get", ServerPlayer.class, int.class)
        .invoke(null, other, 2);
    var insert = Class.forName("dev.chestmagnet.Destinations$Target")
        .getMethod("insert", ItemStack.class, boolean.class);
    var left = (ItemStack) insert.invoke(target, new ItemStack(Items.DIAMOND, 8), false);
    require(left.isEmpty() && slotsA.getStackInSlot(0).getCount() == 64
        && slotsB.getStackInSlot(0).getCount() == 5, "real backpacks receive 3 and 5");
  }

  static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }
}
