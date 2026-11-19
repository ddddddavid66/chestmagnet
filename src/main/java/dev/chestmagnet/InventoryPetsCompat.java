package dev.chestmagnet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.minecraftforge.fml.ModList;

final class InventoryPetsCompat {
  private static final int TOKEN_SLOT = 4;
  private static final String CHEST_NAME = "Space Dungeon";
  private static final ResourceLocation SPACE_DUNGEON = new ResourceLocation("inventorypets:code_structure_space_dungeon");
  private record Handler(Object receiver, Method method) {}

  private static final class NativeHandler {
    private static final Handler INSTANCE = resolve();

    private static Handler resolve() {
      var mod = ModList.get().getModContainerById("inventorypets");
      if (mod.isEmpty() || !mod.get().getModInfo().getVersion().toString().equals("2.2.8")) return null;
      try {
        var type = Class.forName("com.inventorypets.events.DungeonChestLoot");
        var method = type.getMethod("onItemRightClick", RightClickBlock.class);
        return method.getReturnType() == void.class ? new Handler(type.getConstructor().newInstance(), method) : null;
      } catch (ReflectiveOperationException | LinkageError ex) {
        org.slf4j.LoggerFactory.getLogger("chestmagnet").warn("背包宠物开箱接口不兼容；保留虚空地牢占位物", ex);
        return null;
      }
    }
  }

  static boolean prepare(ServerPlayer player, ChestBlockEntity chest) {
    var state = chest.getBlockState();
    if (!state.is(Blocks.CHEST) || !Level.END.equals(player.level().dimension())
        || chest.getCustomName() == null || !CHEST_NAME.equals(chest.getCustomName().getString())
        || !ModList.get().isLoaded("inventorypets") || UnopenedLoot.table(chest) != null) return true;
    var token = chest.getItem(TOKEN_SLOT);
    if (!token.is(Items.POISONOUS_POTATO)) return true;
    // 原版模组会按箱名和马铃薯识别；磁铁更严格，非模板内容只保护、不主动生成。
    if (!isToken(token)
        || token.getCount() != 1 || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return false;
    for (int slot = 0; slot < chest.getContainerSize(); slot++)
      if (slot != TOKEN_SLOT && !chest.getItem(slot).isEmpty()) return false;
    var pos = chest.getBlockPos();
    if (chest.isRemoved() || player.level().getBlockEntity(pos) != chest
        || !insideLoadedDungeon(player.serverLevel(), pos)) return false;
    var handler = NativeHandler.INSTANCE;
    if (handler == null) return false;
    Faults.stage("背包宠物原生生成虚空地牢战利品");
    try {
      // 只调用已核对的原生处理器；不发布事件或执行普通开箱。
      var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
      handler.method().invoke(handler.receiver(), new RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit));
      chest.setChanged();
      if (chest.isRemoved() || player.level().getBlockEntity(pos) != chest
          || chest.getCustomName() != null && CHEST_NAME.equals(chest.getCustomName().getString())
          || isToken(chest.getItem(TOKEN_SLOT)))
        throw new IllegalStateException("背包宠物未按预期完成初始化，已保留现场，不能重抽");
      return true;
    } catch (InvocationTargetException ex) {
      chest.setChanged();
      throw new IllegalStateException("背包宠物生成失败，已保留现场，不能重抽", ex.getCause());
    } catch (ReflectiveOperationException | LinkageError ex) {
      throw new IllegalStateException("背包宠物开箱接口不可用，未提取占位物", ex);
    }
  }

  private static boolean isToken(ItemStack token) {
    return token.is(Items.POISONOUS_POTATO) && token.hasCustomHoverName()
        && "Rebecca".equals(token.getHoverName().getString());
  }

  private static boolean insideLoadedDungeon(ServerLevel level, BlockPos pos) {
    var chunks = level.getChunkSource();
    var chunk = chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(SPACE_DUNGEON);
    if (chunk == null || structure == null) return false;
    // 验证真实生成实例，避免改名的箱子和占位物被用来重复生成战利品；不加载起点。
    var references = chunk.getReferencesForStructure(structure).iterator();
    while (references.hasNext()) {
      long ref = references.nextLong();
      var startChunk = chunks.getChunkNow(ChunkPos.getX(ref), ChunkPos.getZ(ref));
      if (startChunk == null) continue;
      var start = startChunk.getStartForStructure(structure);
      if (start != null && start.isValid()
          && start.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().isInside(pos))) return true;
    }
    return false;
  }
}
