package dev.chestmagnet;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.minecraft.core.registries.BuiltInRegistries;

/** 已验证战利品来源的定向适配，不猜测未知锁字段。 */
public final class SourceExtras {
  public record Special(BlockPos pos) {}

  /** 确定没有生成本次奖励的拒绝，不得误标为转移结果不确定。 */
  public static final class KnownFailure extends IllegalStateException {
    public KnownFailure(String message) {
      super(message);
    }
  }

  /** 把转移时的两半身份一并保留，避免收尾误拆后来放上的同类另一半。 */
  static String blockId(ServerPlayer p, BlockPos at) {
    return BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(at).getBlock()).toString();
  }

  private static Set<Block> specialBlocks;

  static boolean special(net.minecraft.world.level.block.state.BlockState state) {
    if (specialBlocks == null) {
      var set = new HashSet<Block>();
      for (String id : List.of("aquamirae:frozen_chest")) {
        var block =
            BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));
        if (block != null && block != Blocks.AIR) set.add(block);
      }
      specialBlocks = Set.copyOf(set);
    }
    return specialBlocks.contains(state.getBlock());
  }

  static boolean known(BlockEntity be) {
    var id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock());
    return ResourceLocation.fromNamespaceAndPath("aether", "treasure_chest").equals(id);
  }

  static boolean option(ItemStack m, String key) {
    return m == null || !Data.tag(m).contains(key) || Data.tag(m).getBoolean(key);
  }

  static Object call(Object o, String name, Class<?>[] sig, Object... args) {
    try {
      return o.getClass().getMethod(name, sig).invoke(o, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("兼容调用失败: " + name, e);
    }
  }

  static BlockPos pos(Object o) {
    return o instanceof Special s
        ? s.pos()
        : o instanceof net.minecraft.world.entity.Entity e
            ? e.blockPosition()
            : ((BlockEntity) o).getBlockPos();
  }

  static IItemHandler inventory(ServerPlayer p, Object o, ItemStack m) {
    if (o instanceof ItemFrame f) {
      if (!option(m, "CollectFrames")) return null;
      return new IItemHandler() {
        public int getSlots() {
          return 1;
        }

        public ItemStack getStackInSlot(int i) {
          return f.getItem();
        }

        public int getSlotLimit(int i) {
          return 1;
        }

        public boolean isItemValid(int i, ItemStack s) {
          return true;
        }

        public ItemStack extractItem(int i, int n, boolean simulate) {
          var s = f.getItem();
          if (n <= 0 || s.isEmpty()) return ItemStack.EMPTY;
          var taken = s.copy();
          if (!simulate) {
            var data = MapItem.getSavedData(s, f.level());
            if (data != null) {
              data.removedFromFrame(f.getPos(), f.getId());
              data.setDirty();
            }
            s.setEntityRepresentation(null);
            f.setItem(ItemStack.EMPTY);
          }
          return taken;
        }

        public ItemStack insertItem(int i, ItemStack s, boolean simulate) {
          if (!f.isAlive() || !f.getItem().isEmpty()) return s;
          if (!simulate) f.setItem(s);
          return ItemStack.EMPTY;
        }
      };
    }
    if (!option(m, "CollectLocked")) return null;
    if (o instanceof Special s) {
      String id = blockId(p, s.pos());
      if (id.equals("aquamirae:frozen_chest")) {
        Faults.stage("冰冻箱转换");
        var old = p.level().getBlockState(s.pos());
        var state = Blocks.CHEST.defaultBlockState();
        for (var prop : old.getProperties())
          if (prop.getName().equals("facing"))
            state = state.setValue(ChestBlock.FACING, (Direction) old.getValue(prop));
        // 只有确实替换成功且仍是本次新箱子，才能绑定原生战利品表；失败不解引用空BE。
        if (!p.level().setBlock(s.pos(), state, 3)) throw new KnownFailure("冰冻箱转换未成功，原方块未更改");
        var candidate = p.level().getBlockEntity(s.pos());
        if (p.level().getBlockState(s.pos()) != state
            || !(candidate instanceof ChestBlockEntity be))
          throw new IllegalStateException("冰冻箱转换后被其他逻辑替换，未生成奖励");
        var saved = be.saveWithoutMetadata(p.registryAccess());
        if (saved.contains("LootTable") || !saved.getList("Items", 10).isEmpty())
          throw new IllegalStateException("冰冻箱转换后已有外部库存或战利品表，未覆盖现有奖励");
        be.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
            ResourceLocation.parse("aquamirae:chests/frozen_chest")), 0);
        be.setChanged();
        return Sources.inventory(p, be, m);
      }
    }
    if (o instanceof BlockEntity be && known(be)) {
      ((RandomizableContainerBlockEntity) be).unpackLootTable(p);
      return direct((Container) be);
    }
    return null;
  }

  static InteractionHand hand(ServerPlayer p, ItemStack m) {
    return p.getOffhandItem() == m ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
  }

  private static boolean empty(IItemHandler inv) {
    for (int i = 0; i < inv.getSlots(); i++) if (!inv.getStackInSlot(i).isEmpty()) return false;
    return true;
  }

  static IItemHandler direct(Container c) {
    return new InvWrapper(c) {
      @Override
      public ItemStack insertItem(int slot, ItemStack s, boolean sim) {
        var old = c.getItem(slot);
        if (!old.isEmpty() && !ItemStack.isSameItemSameComponents(old, s)) return s;
        int n =
            Math.min(
                s.getCount(), Math.min(c.getMaxStackSize(), s.getMaxStackSize()) - old.getCount());
        if (n <= 0) return s;
        if (!sim) {
          var next = s.copy();
          next.setCount(n + old.getCount());
          c.setItem(slot, next);
          c.setChanged();
        }
        var left = s.copy();
        left.shrink(n);
        return left;
      }
    };
  }

  private static final class LockField {
    static final java.lang.reflect.Field VALUE = find();

    private static java.lang.reflect.Field find() {
      for (var field : BaseContainerBlockEntity.class.getDeclaredFields())
        if (field.getType() == net.minecraft.world.LockCode.class) {
          field.setAccessible(true);
          return field;
        }
      return null;
    }
  }

  private static boolean hasLock(BaseContainerBlockEntity b) {
    if (LockField.VALUE == null) throw new IllegalStateException("原版锁字段不匹配");
    try {
      return !((net.minecraft.world.LockCode) LockField.VALUE.get(b)).unlocksWith(ItemStack.EMPTY);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean needsNearbyFinish(ServerPlayer p, Object o, boolean locked) {
    if (!locked || !(o instanceof BlockEntity be) || be.isRemoved()) return false;
    String id = blockId(p, be.getBlockPos());
    if (Sources.personalLoot(be)) return false;
    if (id.equals("aether:treasure_chest")) return true;
    if (be instanceof BaseContainerBlockEntity b) {
      if (hasLock(b)) return true;
      for (var pos : Sources.of(be).members(p))
        if (!pos.equals(be.getBlockPos())
            && p.level().getBlockEntity(pos) instanceof BaseContainerBlockEntity half
            && hasLock(half)) return true;
    }
    return false;
  }

  static boolean clearLock(BaseContainerBlockEntity b) {
    try {
      if (!hasLock(b)) return false;
      LockField.VALUE.set(b, net.minecraft.world.LockCode.NO_LOCK);
      return true;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean finish(ServerPlayer p, Object o, IItemHandler inv, ItemStack m) {
    if (LootCapture.pending(m)) return false;
    if (!empty(inv)) return false;
    if (!option(m, "CollectLocked")) return true;
    if (o instanceof BlockEntity be) {
      // 回调可能替换方块，绝不能拿旧库存引用拆除后来放上的同类箱子。
      if (be.isRemoved() || p.level().getBlockEntity(be.getBlockPos()) != be) return false;
      String id = blockId(p, be.getBlockPos());
      if (Sources.personalLoot(be)) return true;
      if (id.equals("aether:treasure_chest")) {
        Faults.stage("天境原生永久解锁");
        call(be, "tryUnlock", new Class[] {net.minecraft.world.entity.player.Player.class}, p);
        if ((boolean) call(be, "getLocked", new Class[] {}))
          throw new KnownFailure("天境原生解锁未生效，箱体仍保留");
      }
      if (be instanceof BaseContainerBlockEntity) {
        for (var at : Sources.of(be).members(p)) {
          var half = p.level().getBlockEntity(at);
          if (half instanceof BaseContainerBlockEntity b) {
            if (clearLock(b)) b.setChanged();
          }
        }
      }
    }
    return true;
  }

  /** 调色板先筛选；只在命中的分节逐批定位无BE战利品方块。 */
  static final class Scan {
    final LevelChunk chunk;
    int section, cell;

    Scan(LevelChunk c) {
      chunk = c;
      section = c.getSectionsCount() - 1;
    }

    boolean step(java.util.function.Consumer<Special> found, long deadline) {
      int budget = 512;
      while (section >= 0 && budget > 0 && System.nanoTime() < deadline) {
        var s = chunk.getSection(section);
        if (cell == 0 && !s.getStates().maybeHas(SourceExtras::special)) {
          section--;
          budget--;
          continue;
        }
        int k = 4095 - cell++;
        budget--;
        if (special(s.getBlockState(k & 15, (k >> 8) & 15, (k >> 4) & 15)))
          found.accept(
              new Special(
                  new BlockPos(
                      chunk.getPos().getMinBlockX() + (k & 15),
                      chunk.getMinBuildHeight() + section * 16 + ((k >> 8) & 15),
                      chunk.getPos().getMinBlockZ() + ((k >> 4) & 15))));
        if (cell == 4096) {
          cell = 0;
          section--;
        }
      }
      return section < 0;
    }
  }
}
