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
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.*;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** 已验证战利品来源的定向适配，不猜测未知锁字段。 */
public final class SourceExtras {
  public record Special(BlockPos pos) {}

  /** 确定没有生成本次奖励的拒绝，不得误标为转移结果不确定。 */
  public static final class KnownFailure extends IllegalStateException {
    public KnownFailure(String message) {
      super(message);
    }
  }

  private record TetraPair(BlockEntity main, BlockEntity other) {
    List<BlockPos> positions() {
      return List.of(main.getBlockPos(), other.getBlockPos());
    }
  }

  /** 把转移时的两半身份一并保留，避免收尾误拆后来放上的同类另一半。 */
  private record TetraInventory(TetraPair pair, IItemHandler delegate) implements IItemHandler {
    public int getSlots() {
      return delegate.getSlots();
    }

    public ItemStack getStackInSlot(int slot) {
      return delegate.getStackInSlot(slot);
    }

    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
      return delegate.insertItem(slot, stack, simulate);
    }

    public ItemStack extractItem(int slot, int amount, boolean simulate) {
      return delegate.extractItem(slot, amount, simulate);
    }

    public int getSlotLimit(int slot) {
      return delegate.getSlotLimit(slot);
    }

    public boolean isItemValid(int slot, ItemStack stack) {
      return delegate.isItemValid(slot, stack);
    }
  }

  static String blockId(ServerPlayer p, BlockPos at) {
    return ForgeRegistries.BLOCKS.getKey(p.level().getBlockState(at).getBlock()).toString();
  }

  private static Set<Block> specialBlocks;

  static boolean special(net.minecraft.world.level.block.state.BlockState state) {
    if (specialBlocks == null) {
      var set = new HashSet<Block>();
      for (String id : List.of("aquamirae:frozen_chest", "tetra:forged_crate")) {
        var block =
            ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation(id));
        if (block != null && block != Blocks.AIR) set.add(block);
      }
      specialBlocks = Set.copyOf(set);
    }
    return specialBlocks.contains(state.getBlock());
  }

  static BlockPos tetraOther(ServerPlayer p, BlockPos at) {
    var state = p.level().getBlockState(at);
    if (!blockId(p, at).equals("tetra:forged_container")) return null;
    boolean flipped = false;
    for (var prop : state.getProperties())
      if (prop.getName().equals("flipped")) flipped = (Boolean) state.getValue(prop);
    var facing = state.getValue(HorizontalDirectionalBlock.FACING);
    return at.relative(flipped ? facing.getCounterClockWise() : facing.getClockWise());
  }

  static boolean known(BlockEntity be) {
    var id = ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock());
    return id != null
        && (id.toString().equals("aether:treasure_chest")
            || id.toString().equals("tetra:forged_container"));
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

  static Object tetra(String cls, String name, Class<?>[] sig, Object... args) {
    try {
      return Class.forName("se.mickelus.tetra." + cls).getMethod(name, sig).invoke(null, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Tetra接口不匹配", e);
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
            f.getFramedMapId()
                .ifPresent(
                    id -> {
                      var data = MapItem.getSavedData(id, f.level());
                      if (data != null) {
                        data.removedFromFrame(f.getPos(), f.getId());
                        data.setDirty();
                      }
                    });
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
        var saved = be.saveWithoutMetadata();
        if (saved.contains("LootTable") || !saved.getList("Items", 10).isEmpty())
          throw new IllegalStateException("冰冻箱转换后已有外部库存或战利品表，未覆盖现有奖励");
        be.setLootTable(new ResourceLocation("aquamirae:chests/frozen_chest"), 0);
        be.setChanged();
        return Sources.inventory(p, be, m);
      }
      if (id.equals("tetra:forged_crate")) {
        Faults.stage("Tetra板条箱破坏许可");
        var state = p.level().getBlockState(s.pos());
        checkBreakPermission(p, m, s.pos(), state);
        checkSameState(p, s.pos(), state);
        LootCapture.run(
            m,
            p,
            List.of(s.pos()),
            () -> {
              Faults.stage("Tetra板条箱原生移除");
              removeApprovedBlock(p, s.pos(), state);
              // TOOL固定为磁铁；不能因磁铁在背包里就意外套用当前手持工具的时运。
              Faults.stage("Tetra板条箱奖励生成");
              tetraLoot(new ResourceLocation("tetra:forged/crate_content"), p, m, s.pos(), state)
                  .forEach(LootCapture::add);
              return null;
            });
        return new ItemStackHandler(0);
      }
    }
    if (o instanceof BlockEntity be && known(be)) {
      if (blockId(p, be.getBlockPos()).equals("tetra:forged_container")) {
        var pair = tetraPair(p, be);
        if (pair == null) return null;
        var main = pair.main();
        LootCapture.run(
            m,
            p,
            pair.positions(),
            () -> {
              Faults.stage("Tetra原生敲锁");
              for (int i = 0; i < 4; i++)
                for (int k = 0;
                    k < 16 && (boolean) call(main, "isLocked", new Class[] {int.class}, i);
                    k++) {
                  requirePair(p, pair);
                  call(
                      main,
                      "breakLock",
                      new Class[] {
                        net.minecraft.world.entity.player.Player.class,
                        int.class,
                        InteractionHand.class
                      },
                      p,
                      i,
                      hand(p, m));
                }
              for (int i = 0; i < 4; i++)
                if ((boolean) call(main, "isLocked", new Class[] {int.class}, i))
                  throw new IllegalStateException("锻造容器锁完整度异常，未继续开盖");
              Faults.stage("Tetra原生开盖");
              for (int k = 0; k < 16 && !(boolean) call(main, "isOpen", new Class[] {}); k++) {
                requirePair(p, pair);
                call(main, "open", new Class[] {net.minecraft.world.entity.player.Player.class}, p);
              }
              requirePair(p, pair);
              if (!(boolean) call(main, "isOpen", new Class[] {}))
                throw new IllegalStateException("锻造容器开盖未完成，未读取库存");
              return null;
            });
        var handler =
            main.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .orElse(null);
        return handler == null ? null : new TetraInventory(pair, handler);
      }
      ((RandomizableContainerBlockEntity) be).unpackLootTable(p);
      return direct((Container) be);
    }
    return null;
  }

  static InteractionHand hand(ServerPlayer p, ItemStack m) {
    return p.getOffhandItem() == m ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
  }

  private static boolean flipped(BlockState state) {
    for (var prop : state.getProperties())
      if (prop.getName().equals("flipped")) return (Boolean) state.getValue(prop);
    throw new KnownFailure("锻造容器缺少flipped属性，兼容接口不匹配");
  }

  /** Tetra原生getOrDelegate只按相对位置查找；磁铁必须先证实两格真是同一组。 */
  private static TetraPair tetraPair(ServerPlayer p, BlockEntity be) {
    if (be.isRemoved() || p.level().getBlockEntity(be.getBlockPos()) != be)
      throw new KnownFailure("锻造容器已被替换");
    var at = be.getBlockPos();
    var state = p.level().getBlockState(at);
    var otherAt = tetraOther(p, at);
    if (otherAt == null) throw new KnownFailure("锻造容器类型已变化");
    if (!p.level().hasChunkAt(otherAt)) return null;
    var otherState = p.level().getBlockState(otherAt);
    if (!otherState.is(state.getBlock())
        || otherState.getValue(HorizontalDirectionalBlock.FACING)
            != state.getValue(HorizontalDirectionalBlock.FACING)
        || flipped(otherState) == flipped(state)
        || !at.equals(tetraOther(p, otherAt))) throw new KnownFailure("锻造容器两半不匹配，未更改箱体");
    var other = p.level().getBlockEntity(otherAt);
    if (other == null || other.isRemoved() || other.getClass() != be.getClass())
      throw new KnownFailure("锻造容器另一半库存不存在");
    var pair = flipped(state) ? new TetraPair(other, be) : new TetraPair(be, other);
    if (call(pair.main(), "getOrDelegate", new Class[] {}) != pair.main()
        || call(pair.other(), "getOrDelegate", new Class[] {}) != pair.main())
      throw new KnownFailure("锻造容器库存未指向同一主半");
    return pair;
  }

  private static void requirePair(ServerPlayer p, TetraPair expected) {
    TetraPair now;
    try {
      now = tetraPair(p, expected.main());
    } catch (KnownFailure e) {
      throw new IllegalStateException("处理途中锻造容器配对发生变化", e);
    }
    if (now == null || now.main() != expected.main() || now.other() != expected.other())
      throw new IllegalStateException("处理途中锻造容器另一半已卸载或替换");
  }

  private static boolean empty(IItemHandler inv) {
    for (int i = 0; i < inv.getSlots(); i++) if (!inv.getStackInSlot(i).isEmpty()) return false;
    return true;
  }

  private static void checkSameState(ServerPlayer p, BlockPos at, BlockState state) {
    if (p.level().getBlockState(at) != state) throw new KnownFailure("权限检查期间来源状态变化，未继续拆除");
  }

  /** 对每个将被原生连带移除的位置检查一次许可，绝不靠直接setBlock绕过保护。 */
  private static void checkBreakPermission(
      ServerPlayer p, ItemStack m, BlockPos at, BlockState state) {
    var gameType = p.gameMode.getGameModeForPlayer();
    if (p.blockActionRestricted(p.level(), at, gameType)) throw new KnownFailure("当前游戏模式禁止破坏该来源");
    if (ForgeHooks.onBlockBreakEvent(p.level(), gameType, p, at) == -1)
      throw new KnownFailure("Forge破坏检查被取消（可能为手持物限制或保护事件），位置 " + at.toShortString());
    checkSameState(p, at, state);
    if (m != null && m.onBlockStartBreak(at, p)) throw new KnownFailure("磁铁的破坏起始钩子拒绝继续处理");
    checkSameState(p, at, state);
  }

  /** 等价于Tetra EffectHelper的harvest=false分支；许可已预检，避免重复发布BreakEvent。 */
  private static void removeApprovedBlock(ServerPlayer p, BlockPos at, BlockState state) {
    checkSameState(p, at, state);
    boolean removed =
        state
            .getBlock()
            .onDestroyedByPlayer(state, p.level(), at, p, false, p.level().getFluidState(at));
    if (!removed) {
      if (p.level().getBlockState(at) == state) throw new KnownFailure("方块原生移除返回false，来源仍未改变");
      throw new IllegalStateException("方块移除返回false但世界已变化，已停止该来源");
    }
    state.getBlock().destroy(p.level(), at, state);
    if (p.level().getBlockState(at).is(state.getBlock()))
      throw new IllegalStateException("方块移除后仍存在同类来源，未再次生成奖励");
  }

  /** 复用原战利品表及原BLOCK参数集，仅明确磁铁和来源位置，禁止借用玩家当前工具。 */
  public static List<ItemStack> tetraLoot(
      ResourceLocation table, ServerPlayer p, ItemStack magnet, BlockPos at, BlockState state) {
    var params =
        new LootParams.Builder(p.serverLevel())
            .withLuck(p.getLuck())
            .withParameter(LootContextParams.THIS_ENTITY, p)
            .withParameter(LootContextParams.BLOCK_STATE, state)
            .withParameter(LootContextParams.TOOL, magnet == null ? ItemStack.EMPTY : magnet)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
            .create(LootContextParamSets.BLOCK);
    return p.serverLevel().getServer().getLootData().getLootTable(table).getRandomItems(params);
  }

  static IItemHandler direct(Container c) {
    return new InvWrapper(c) {
      @Override
      public ItemStack insertItem(int slot, ItemStack s, boolean sim) {
        var old = c.getItem(slot);
        if (!old.isEmpty() && !ItemStack.isSameItemSameTags(old, s)) return s;
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
    if (id.equals("aether:treasure_chest") || id.equals("tetra:forged_container")
        || ChurchContainers.known(be)) return true;
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
      if (ChurchContainers.known(be)) ChurchContainers.finish(be);
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
      if (id.equals("tetra:forged_container")) {
        var pair = tetraPair(p, be);
        if (pair == null) return false;
        if (!(inv instanceof TetraInventory captured)
            || captured.pair().main() != pair.main()
            || captured.pair().other() != pair.other())
          throw new KnownFailure("锻造容器转移后两半身份变化，未拆除替换物");
        var main = pair.main();
        var live =
            main.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .orElse(null);
        if (live == null || live != captured.delegate() || !empty(live)) return false;
        // 主半破坏会触发邻居更新删除另一半；必须先让双方的保护检查全部通过。
        var state = main.getBlockState();
        var otherState = pair.other().getBlockState();
        Faults.stage("Tetra主半拆除许可");
        checkBreakPermission(p, m, main.getBlockPos(), state);
        Faults.stage("Tetra另一半拆除许可");
        checkBreakPermission(p, m, pair.other().getBlockPos(), otherState);
        requirePair(p, pair);
        checkSameState(p, main.getBlockPos(), state);
        checkSameState(p, pair.other().getBlockPos(), otherState);
        if (!empty(live) || LootCapture.pending(m)) return false;
        return LootCapture.run(
            m,
            p,
            pair.positions(),
            () -> {
              requirePair(p, pair);
              if (!empty(live)) return false;
              Faults.stage("Tetra双格容器原生拆除");
              removeApprovedBlock(p, main.getBlockPos(), state);
              if (p.level().getBlockState(pair.other().getBlockPos()).is(state.getBlock()))
                throw new IllegalStateException("锻造容器另一半未被原生流程移除，未重复结算");
              // 只结算主半的原生拆除表一次；两半各自水浸状态由原生移除/邻居更新保留。
              Faults.stage("Tetra主半拆除产物生成");
              Block.getDrops(state, p.serverLevel(), main.getBlockPos(), main, p, m)
                  .forEach(LootCapture::add);
              return !LootCapture.pending(m);
            });
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
