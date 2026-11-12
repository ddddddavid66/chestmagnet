package dev.chestmagnet;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.*;
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

  public static boolean eligible(Object o) {
    if (o instanceof SourceExtras.Special) return true;
    if (o instanceof Entity e) {
      if (e instanceof net.minecraft.world.entity.decoration.ItemFrame
          || e instanceof MinecartChest || e instanceof MinecartHopper) return true;
      if (!(e instanceof AbstractMinecartContainer)) return false;
      var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
      return id != null && id.getNamespace().equals("lootr");
    }
    if (!(o instanceof BlockEntity b)) return false;
    var id =
        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b.getBlockState().getBlock());
    if (id == null
        || id.getNamespace().startsWith("sophisticated")
        || id.getNamespace().startsWith("refinedstorage")) return false;
    if (id.getNamespace().equals("lootr")) return b instanceof Container;
    return SourceExtras.known(b)
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

  public static IItemHandler inventory(
      ServerPlayer p, Object o, net.minecraft.world.item.ItemStack magnet) {
    if (o instanceof SourceExtras.Special
        || o instanceof net.minecraft.world.entity.decoration.ItemFrame
        || (o instanceof BlockEntity b && SourceExtras.known(b)))
      return SourceExtras.inventory(p, o, magnet);
    if (o instanceof BlockEntity be && ChurchContainers.known(be)) return ChurchContainers.inventory(p, be, magnet);
    String ns =
        o instanceof Entity e
            ? net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getKey(e.getType())
                .getNamespace()
            : net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(((BlockEntity) o).getBlockState().getBlock())
                .getNamespace();
    if (ns.equals("lootr")) {
      if (!ModList.get().isLoaded("lootr")) return null;
      Container c = LootrCompat.inventory(p, o);
      return c == null ? null : new InvWrapper(c);
    }
    if (o instanceof BaseContainerBlockEntity b
        && !SourceExtras.option(magnet, "CollectLocked")
        && !b.canOpen(p)) return null;
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
      for (var at : of(o).members(p))
        if (p.level().getBlockEntity(at) instanceof RandomizableContainerBlockEntity half)
          half.unpackLootTable(p);
      return new InvWrapper(c);
    }
    if (o instanceof RandomizableContainerBlockEntity b) b.unpackLootTable(p);
    if (o instanceof AbstractMinecartContainer c) c.unpackChestVehicleLootTable(p);
    if (o instanceof Container c) return new InvWrapper(c);
    return o instanceof BlockEntity be
        ? be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
            .orElse(null)
        : null;
  }
}
