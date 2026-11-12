package dev.chestmagnet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** 独立于箱子范围：只识别这座绯红教堂模板预置的奖励基座。 */
final class ChapelPedestals {
  static final String CHURCH = "goety_revelation:church";
  private static final ResourceLocation PEDESTAL = new ResourceLocation("goety", "pedestal");
  private static final ResourceLocation ALTAR = new ResourceLocation("goety", "dark_altar");

  record Layout(Map<BlockPos, ItemStack> rewards, List<BlockPos> altars) {
    static final Layout EMPTY = new Layout(Map.of(), List.of());

    IItemHandler inventory(ServerLevel level, BlockEntity be) {
      var expected = rewards.get(be.getBlockPos());
      if (expected == null || !PEDESTAL.equals(ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock())))
        return null;
      var handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
      if (handler == null || handler.getSlots() != 1) return null;
      var held = handler.getStackInSlot(0);
      if (held.isEmpty() || held.getCount() > expected.getCount() || !ItemStack.isSameItemSameTags(held, expected)
          || !canTouch(level, be)) return null;
      return new IItemHandler() {
        public int getSlots() { return 1; }
        public ItemStack getStackInSlot(int slot) { return handler.getStackInSlot(slot); }
        public int getSlotLimit(int slot) { return handler.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return handler.isItemValid(slot, stack); }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
          var left = handler.insertItem(slot, stack, simulate);
          if (!simulate && left.getCount() != stack.getCount()) be.setChanged();
          return left;
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
          var actual = handler.extractItem(slot, amount, simulate);
          if (!simulate && !actual.isEmpty()) be.setChanged();
          return actual;
        }
      };
    }

    private boolean canTouch(ServerLevel level, BlockEntity pedestal) {
      try {
        // 新版有原生锁；旧版检查同一模板实例里的仪式状态。
        try {
          if (Boolean.TRUE.equals(pedestal.getClass().getMethod("isLocked").invoke(pedestal))) return false;
        } catch (NoSuchMethodException legacy) {
          // 旧 Goety 的基座没有锁接口。
        }
        for (var pos : altars) {
          var at = new net.minecraft.world.level.ChunkPos(pos);
          var chunk = level.getChunkSource().getChunkNow(at.x, at.z);
          if (chunk == null) continue;
          if (!ALTAR.equals(ForgeRegistries.BLOCKS.getKey(chunk.getBlockState(pos).getBlock()))) continue;
          var altar = chunk.getBlockEntity(pos);
          if (altar == null || altar.getClass().getField("currentRitualRecipe").get(altar) != null
              || altar.getClass().getField("currentRitualRecipeId").get(altar) != null) return false;
        }
        return true;
      } catch (ReflectiveOperationException | LinkageError incompatible) {
        return false;
      }
    }
  }

  static Layout from(StructureStart start) {
    if (!ForgeRegistries.BLOCKS.containsKey(PEDESTAL) || !ForgeRegistries.BLOCKS.containsKey(ALTAR))
      return Layout.EMPTY;
    var pedestal = ForgeRegistries.BLOCKS.getValue(PEDESTAL);
    var altar = ForgeRegistries.BLOCKS.getValue(ALTAR);
    var rewards = new LinkedHashMap<BlockPos, ItemStack>();
    var altars = new ArrayList<BlockPos>();
    for (var piece : start.getPieces()) {
      if (!(piece instanceof TemplateStructurePiece template)) continue;
      var source = template.placeSettings();
      // 不沿用区块裁剪框或随机处理器；这里只还原已经生成的模板坐标。
      var settings = new StructurePlaceSettings().setRotation(source.getRotation())
          .setMirror(source.getMirror()).setRotationPivot(source.getRotationPivot());
      for (var entry : template.template().filterBlocks(template.templatePosition(), settings, pedestal)) {
        if (entry.nbt() == null || !piece.getBoundingBox().isInside(entry.pos())) continue;
        var items = entry.nbt().getCompound("inventory").getList("Items", Tag.TAG_COMPOUND);
        if (items.size() != 1 || items.getCompound(0).getByte("Slot") != 0) continue;
        var stack = ItemStack.of(items.getCompound(0));
        if (!stack.isEmpty()) rewards.put(entry.pos().immutable(), stack);
      }
      for (var entry : template.template().filterBlocks(template.templatePosition(), settings, altar))
        if (piece.getBoundingBox().isInside(entry.pos())) altars.add(entry.pos().immutable());
    }
    return new Layout(Map.copyOf(rewards), List.copyOf(altars));
  }

  private ChapelPedestals() {}
}
