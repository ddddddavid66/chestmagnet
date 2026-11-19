package dev.rsqa124.mixin;

import dev.rsqa124.FreshTreasureTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelChunk.class, remap = false)
public abstract class FreshTreasureChunkMixin {
  @Inject(method = {"setBlockState", "m_6978_"}, at = @At("RETURN"))
  private void freshWrite(BlockPos pos, BlockState next, boolean moving, CallbackInfoReturnable<BlockState> ci) {
    if (ci.getReturnValue() != null) FreshTreasureTrace.write((LevelChunk)(Object)this, pos, "BLOCK_RETURN");
  }

  @Inject(method = {"setBlockEntity", "m_142169_"}, at = @At("RETURN"))
  private void freshSetEntity(BlockEntity be, CallbackInfo ci) {
    var chunk = (LevelChunk)(Object)this;
    if (FreshTreasureTrace.watching(be.getBlockPos()) && chunk.getBlockEntities().get(be.getBlockPos()) == be)
      FreshTreasureTrace.write(chunk, be.getBlockPos(), "BE_SET");
  }

  @Inject(method = {"removeBlockEntity", "m_8114_"}, at = @At("RETURN"))
  private void freshRemoveEntity(BlockPos pos, CallbackInfo ci) {
    FreshTreasureTrace.write((LevelChunk)(Object)this, pos, "BE_REMOVE_RETURN");
  }
}
