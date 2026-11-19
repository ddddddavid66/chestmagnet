package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.*;
import net.minecraftforge.items.wrapper.InvWrapper;

public final class Sources {
  private static final net.minecraft.tags.TagKey<Block> CHESTS =
      BlockTags.create(new ResourceLocation("forge", "chests"));
  private static final net.minecraft.tags.TagKey<Block> BARRELS =
      BlockTags.create(new ResourceLocation("forge", "barrels"));
  private static final net.minecraft.tags.TagKey<Block> LOOTR_CHESTS =
      BlockTags.create(new ResourceLocation("lootr", "chests"));
  private static final net.minecraft.tags.TagKey<Block> LOOTR_TRAPPED =
      BlockTags.create(new ResourceLocation("lootr", "trapped_chests"));
  private static final ResourceLocation QUARK_CRATE = new ResourceLocation("quark", "crate");
  private static final ResourceLocation QUARK_CHEST_BOAT = new ResourceLocation("quark", "quark_chest_boat");

  public record Ref(BlockPos pos, UUID entity) {
    public Object resolve(ServerPlayer p) {
      if (entity != null) {
        var e = p.serverLevel().getEntity(entity);
        return e != null && e.isAlive() ? e : null;
      }
      return p.serverLevel().hasChunkAt(pos)
          ? (SourceExtras.special(p.level().getBlockState(pos))
              ? new SourceExtras.Special(pos)
              : p.serverLevel().getBlockEntity(pos))
          : null;
    }

    public String key(ServerPlayer p) {
      return entity != null
          ? "E:" + entity
          : "B:" + p.level().dimension().location() + ":" + canonical(p, pos).asLong();
    }

    public String label(ServerPlayer p) {
      var o = resolve(p);
      String name =
          o instanceof Entity e
              ? e.getName().getString()
              : o instanceof BlockEntity be
                  ? be.getBlockState().getBlock().getName().getString()
                  : "容器";
      BlockPos at = o instanceof Entity e ? e.blockPosition() : pos;
      return name
          + " · "
          + p.level().dimension().location()
          + " · "
          + at.getX()
          + ","
          + at.getY()
          + ","
          + at.getZ();
    }

    public List<BlockPos> members(ServerPlayer p) {
      if (entity != null) return List.of();
      var other = otherHalf(p, pos);
      return other == null ? List.of(pos) : List.of(pos, other);
    }
  }

  private static BlockPos otherHalf(ServerPlayer p, BlockPos pos) {
    if (personalLoot(p.level().getBlockEntity(pos))) return null;
    var s = p.level().getBlockState(pos);
    if (SourceExtras.blockId(p, pos).equals("tetra:forged_container")) {
      var other = SourceExtras.tetraOther(p, pos);
      return other != null
              && p.level().hasChunkAt(other)
              && p.level().getBlockState(other).is(s.getBlock())
          ? other
          : null;
    }
    if (s.getBlock() instanceof ChestBlock
        && s.hasProperty(ChestBlock.TYPE)
        && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
      var other = pos.relative(ChestBlock.getConnectedDirection(s));
      if (p.level().hasChunkAt(other) && p.level().getBlockState(other).is(s.getBlock()))
        return other;
    }
    return null;
  }

  private static BlockPos canonical(ServerPlayer p, BlockPos pos) {
    var o = otherHalf(p, pos);
    return o != null && o.asLong() < pos.asLong() ? o : pos;
  }

  // 个人战利品容器不一定用 lootr 命名空间（例如 Quark 变种）。
  static boolean personalLoot(Object source) {
    if (ModList.get().isLoaded("lootr")) {
      try {
        if (LootrCompat.supports(source)) return true;
      } catch (LinkageError ignored) {
        // 已知 Lootr 标记仍按个人库存处理，不能退回普通箱。
      }
    }
    if (source instanceof BlockEntity be) {
      var state = be.getBlockState();
      var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
      return id != null && (id.getNamespace().equals("lootr")
          || id.getNamespace().equals("quark") && id.getPath().startsWith("lootr_")
          || state.is(LOOTR_CHESTS) || state.is(LOOTR_TRAPPED));
    }
    if (!(source instanceof AbstractMinecartContainer e)) return false;
    var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
    return id != null && id.getNamespace().equals("lootr");
  }

  static boolean quarkCrate(Object source) {
    return source instanceof BaseContainerBlockEntity be &&
        QUARK_CRATE.equals(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock()));
  }

  public static boolean eligible(Object o) {
    if (o instanceof SourceExtras.Special) return true;
    if (o instanceof Entity e) {
      if (e instanceof net.minecraft.world.entity.decoration.ItemFrame
          || e instanceof MinecartChest || e instanceof MinecartHopper) return true;
      return personalLoot(e) || e instanceof ChestBoat
          && QUARK_CHEST_BOAT.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
    }
    if (!(o instanceof BlockEntity b)) return false;
    var id =
        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b.getBlockState().getBlock());
    if (id == null
        || id.getNamespace().startsWith("sophisticated")
        || id.getNamespace().startsWith("refinedstorage")
        || id.getNamespace().equals("beyonddimensions")) return false;
    if (personalLoot(b)) return true;
    return quarkCrate(b)
        || SourceExtras.known(b)
        || b instanceof ChestBlockEntity
        || b instanceof BrewingStandBlockEntity
        || ChurchContainers.known(b)
        || b instanceof BarrelBlockEntity
        || b instanceof ShulkerBoxBlockEntity
        || b.getBlockState().is(CHESTS)
        || b.getBlockState().is(BARRELS);
  }

  public static Ref of(Object o) {
    return o instanceof Entity e ? new Ref(null, e.getUUID()) : new Ref(SourceExtras.pos(o), null);
  }

  public static boolean inside(
      ServerPlayer p, net.minecraft.world.item.ItemStack magnet, Object o) {
    BlockPos at = SourceExtras.pos(o), center = p.blockPosition();
    return Math.abs((long) at.getX() - center.getX()) <= Data.radius(magnet, "X")
        && Math.abs((long) at.getY() - center.getY()) <= Data.radius(magnet, "Y")
        && Math.abs((long) at.getZ() - center.getZ()) <= Data.radius(magnet, "Z");
  }

  public static IItemHandler inventory(ServerPlayer p, Object o) {
    return inventory(p, o, null);
  }

  static List<BlockPos> pendingLootrConversion(ServerPlayer p, Object o) {
    if (!(o instanceof RandomizableContainerBlockEntity tile)
        || !ModList.get().isLoaded("lootr") || personalLoot(tile)) return List.of();
    var level = p.serverLevel();
    boolean first = awaitingLootr(tile, level);
    var state = tile.getBlockState();
    var other = state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE ? otherHalf(p, tile.getBlockPos()) : null;
    boolean second = other != null && level.getBlockEntity(other) instanceof RandomizableContainerBlockEntity half
        && !personalLoot(half) && awaitingLootr(half, level);
    if (!first && !second) return List.of();
    return other == null ? List.of(tile.getBlockPos()) : List.of(tile.getBlockPos(), other);
  }

  private static boolean awaitingLootr(RandomizableContainerBlockEntity tile, net.minecraft.server.level.ServerLevel level) {
    try {
      return LootrCompat.awaitingConversion(tile, level);
    } catch (LinkageError incompatibleLootr) {
      // 未知接口时保护未生成的战利品，不能提前把 Lootr 候选当普通库存取走。
      return UnopenedLoot.table(tile) != null;
    }
  }

  public static IItemHandler inventory(
      ServerPlayer p, Object o, net.minecraft.world.item.ItemStack magnet) {
    if (BossProtection.protects(o, magnet)) return null;
    if (personalLoot(o)) {
      if (!ModList.get().isLoaded("lootr")) return null;
      try {
        if (!LootrCompat.supports(o)) return null;
        Container c = LootrCompat.inventory(p, o, SourceExtras.option(magnet, "CollectLocked"));
        return c == null ? null : new InvWrapper(c);
      } catch (LinkageError ignored) {
        return null;
      }
    }
    if (!pendingLootrConversion(p, o).isEmpty()) return null;
    if (o instanceof SourceExtras.Special
        || o instanceof net.minecraft.world.entity.decoration.ItemFrame
        || (o instanceof BlockEntity b && SourceExtras.known(b)))
      return SourceExtras.inventory(p, o, magnet);
    if (o instanceof BlockEntity be && ChurchContainers.known(be)) return ChurchContainers.inventory(p, be, magnet);
    if (o instanceof BaseContainerBlockEntity b
        && !SourceExtras.option(magnet, "CollectLocked")
        && !b.canOpen(p)) return null;
    if (quarkCrate(o)) {
      var crate = (BlockEntity) o;
      // 原生 Container.removeItem 在 Quark 中只模拟；实际抽取和回填走同一能力。
      return crate.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
          .map(handler -> markCrateChanges(crate, handler)).orElse(null);
    }
    if (o instanceof ChestBlockEntity chest) {
      var state = chest.getBlockState();
      if (state.hasProperty(ChestBlock.TYPE)
          && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
          && otherHalf(p, chest.getBlockPos()) == null) return null;
      var c =
          ChestBlock.getContainer(
              (ChestBlock) state.getBlock(), state, p.level(), chest.getBlockPos(), true);
      if (c == null) return null;
      if (c instanceof CompoundContainer pair) {
        for (var at : of(o).members(p)) {
          var b = p.level().getBlockEntity(at);
          if (b instanceof BaseContainerBlockEntity lock
              && !SourceExtras.option(magnet, "CollectLocked")
              && !lock.canOpen(p)) return null;
        }
      }
        for (var at : of(o).members(p)) {
          if (p.level().getBlockEntity(at) instanceof ChestBlockEntity half
              && !InventoryPetsCompat.prepare(p, half)) return null;
        }
        for (var at : of(o).members(p))
          if (p.level().getBlockEntity(at) instanceof RandomizableContainerBlockEntity half)
            half.unpackLootTable(p);
      return new InvWrapper(c);
    }
    if (o instanceof RandomizableContainerBlockEntity b) b.unpackLootTable(p);
    if (o instanceof ContainerEntity c) c.unpackChestVehicleLootTable(p);
    if (o instanceof Container c) return new InvWrapper(c);
    return o instanceof BlockEntity be
        ? be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
            .orElse(null)
        : null;
  }

  private static IItemHandler markCrateChanges(BlockEntity crate, IItemHandler handler) {
    return new IItemHandler() {
      public int getSlots() { return handler.getSlots(); }
      public ItemStack getStackInSlot(int slot) { return handler.getStackInSlot(slot); }
      public int getSlotLimit(int slot) { return handler.getSlotLimit(slot); }
      public boolean isItemValid(int slot, ItemStack stack) { return handler.isItemValid(slot, stack); }
      public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        var remainder = handler.insertItem(slot, stack, simulate);
        if (!simulate && remainder.getCount() < stack.getCount()) crate.setChanged();
        return remainder;
      }
      public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var stack = handler.extractItem(slot, amount, simulate);
        if (!simulate && !stack.isEmpty()) crate.setChanged();
        return stack;
      }
    };
  }
}
