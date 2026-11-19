package dev.rsqa124;

import dev.chestmagnet.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

final class TrackingChecks {
  static final BlockPos STAND = new BlockPos(3, 120, 3);
  static final BlockPos BOX = STAND.east(2);
  static final BlockPos CTRL = STAND.west(3);
  static final Class<?> TRACK = type("dev.chestmagnet.Tracking");
  static final Class<?> RULE = type("dev.chestmagnet.Tracking$Rule");
  static final Class<?> TOAST = type("dev.chestmagnet.TrackingToast");
  static final String PAPER = "minecraft:paper/0/";
  static final String SWORD = "minecraft:diamond_sword/0/";
  static volatile String failure;
  static volatile boolean prepared, delivered;

  static void require(boolean valid, String message) {
    if (!valid) throw new IllegalStateException(message);
  }

  static Class<?> type(String name) {
    try { return Class.forName(name); }
    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
  }

  static Object call(Class<?> type, String name, Class<?>[] signature, Object... args) throws ReflectiveOperationException {
    var m = type.getDeclaredMethod(name, signature);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  static Object selection(ItemStack stack) throws ReflectiveOperationException {
    return call(TRACK, "selection", new Class<?>[] { ItemStack.class }, stack);
  }

  static void toggle(ServerPlayer p, ItemStack item) throws ReflectiveOperationException {
    require((Boolean)call(TRACK, "toggle", new Class<?>[] { ServerPlayer.class, RULE }, p, selection(item)), "toggle test fixture");
  }

  static boolean match(ServerPlayer p, ItemStack stack) throws ReflectiveOperationException {
    return call(TRACK, "match", new Class<?>[] { ServerPlayer.class, ItemStack.class }, p, stack) != null;
  }

  static Destinations.Target target(ServerPlayer p, int mode) throws ReflectiveOperationException {
    return (Destinations.Target)call(Destinations.class, "tracked", new Class<?>[] { ServerPlayer.class, int.class }, p, mode);
  }

  static ItemStack item(String id) {
    var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
    if (item == null) throw new IllegalStateException("missing " + id);
    return new ItemStack(item);
  }

  static void setup(ServerPlayer p, String profile) {
    try {
      p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      p.getInventory().clearContent();
      p.getInventory().selected = 0;
      p.serverLevel().setBlockAndUpdate(STAND.below(), Blocks.STONE.defaultBlockState());
      p.teleportTo(3.5, 120, 3.5);
      p.setXRot(-70);
      var magnet = new ItemStack(ChestMagnet.MAGNET.get());
      Data.tag(magnet).putBoolean("Enabled", false);
      Data.id(magnet);
      p.setItemInHand(InteractionHand.MAIN_HAND, magnet);
      if (!profile.equals("minimal")) verifyIdentities(p);
      if (profile.equals("current")) TrackingNetworks.setup(p);
      prepared = true;
    } catch (Exception | LinkageError e) { fail(e); }
  }

  private static ItemStack scroll(String id, String appearance) {
    var stack = item("tetra:scroll_rolled");
    var entries = new ListTag();
    var entry = new CompoundTag();
    entry.putString("key", id);
    entry.putString("glyphs", appearance);
    var effects = new ListTag();
    effects.add(StringTag.valueOf("test:" + id));
    entry.put("effects", effects);
    entries.add(entry);
    stack.getOrCreateTagElement("BlockEntityTag").put("data", entries);
    return stack;
  }

  private static void verifyIdentities(ServerPlayer p) throws ReflectiveOperationException {
    var book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.SHARPNESS, 3));
    var other = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.SHARPNESS, 4));
    toggle(p, book);
    require(match(p, book) && !match(p, other), "exact enchantment");
    toggle(p, book);
    var scroll = scroll("first", "aaaa");
    toggle(p, scroll);
    require(match(p, scroll("first", "bbbb")) && !match(p, scroll("second", "aaaa")), "canonical Tetra identity");
    require(selection(item("tetra:scroll_rolled")) == null, "placeholder rule");
    toggle(p, scroll);
    System.out.println("TRACK_IDENTITY_PASS books, Tetra stable and distinct");
  }

  static void deliver(ServerPlayer p, String profile) {
    try {
      require(match(p, new ItemStack(Items.DIAMOND_SWORD)), "UI created sword tracking rule");
      var sword = new ItemStack(Items.DIAMOND_SWORD);
      sword.enchant(Enchantments.SHARPNESS, 3);
      sword.setDamageValue(5);
      require(match(p, sword), "generic blade includes modified stack");
      var level = p.serverLevel();
      level.setBlockAndUpdate(BOX, Blocks.CHEST.defaultBlockState());
      var chest = (ChestBlockEntity) level.getBlockEntity(BOX);
      chest.setItem(0, sword);
      Data.tag(p.getMainHandItem()).putBoolean("Enabled", true);

      if (!profile.equals("minimal")) TrackingBags.deliver(p);
      if (profile.equals("current")) TrackingNetworks.deliver(p);
      delivered = true;
    } catch (Exception | LinkageError e) { fail(e); }
  }

  static void fail(Throwable e) {
    failure = e.toString();
    org.slf4j.LoggerFactory.getLogger("tracking-qa").error("TRACK_FAIL", e);
  }
}
