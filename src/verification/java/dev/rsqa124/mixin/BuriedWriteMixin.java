package dev.rsqa124.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
@Mixin(value=LevelChunk.class,remap=false)
public abstract class BuriedWriteMixin {
 @Inject(method={"setBlockState","m_6978_"},at=@At("HEAD"),require=1)
 private void write(BlockPos pos,BlockState next,boolean moving,CallbackInfoReturnable<BlockState> ci)throws Exception{

  // Only enabled by the dedicated diagnostic property; ordinary QA paths avoid reflection.
  if(!System.getProperty("chestmagnet.repair128","").startsWith("buried"))return;
  if (!dev.rsqa124.BuriedProbe.watched.contains(pos)) return;
  var chunk=(LevelChunk)(Object)this;
  var be=chunk.getBlockEntities().get(pos);
  String identity=be==null ? "null" : be.getClass().getName();
  if (be!=null) try { identity+=" uuid="+be.getClass().getMethod("getTileId").invoke(be); } catch (ReflectiveOperationException ignored) {}
  dev.rsqa124.BuriedProbe.trace(pos,chunk.getBlockState(pos)+" BE="+identity,next.toString());
 }
 @Inject(method={"setBlockEntity","m_142169_"},at=@At("HEAD"),require=1)
 private void setEntity(net.minecraft.world.level.block.entity.BlockEntity be,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
  if(!System.getProperty("chestmagnet.repair128","").startsWith("buried") || !dev.rsqa124.BuriedProbe.watched.contains(be.getBlockPos()))return;
  var chunk=(LevelChunk)(Object)this;var old=chunk.getBlockEntities().get(be.getBlockPos());
  dev.rsqa124.BuriedProbe.trace(be.getBlockPos(),"BE "+(old==null?"null":old.getClass().getName()),"SET_BE "+be.getClass().getName());
 }
 @Inject(method={"removeBlockEntity","m_8114_"},at=@At("HEAD"),require=1)
 private void removeEntity(BlockPos pos,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
  if(!System.getProperty("chestmagnet.repair128","").startsWith("buried") || !dev.rsqa124.BuriedProbe.watched.contains(pos))return;
  var chunk=(LevelChunk)(Object)this;var old=chunk.getBlockEntities().get(pos);
  dev.rsqa124.BuriedProbe.trace(pos,"BE "+(old==null?"null":old.getClass().getName()),"REMOVE_BE");
 }
}
