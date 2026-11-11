package dev.chestmagnet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.items.IItemHandler;
/** Goety 独立容器：复用原生带玩家的解包，跳过双箱合并但不重抽掉落。 */
final class ChurchContainers {
 static String id(BlockEntity b){return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b.getBlockState().getBlock()).toString();}
 static boolean known(BlockEntity b){return java.util.Set.of("goety:lofty_chest","goety:crypt_chest","goety:pithos").contains(id(b));}
 static boolean locked(BlockEntity b){for(var prop:b.getBlockState().getProperties())if(prop.getName().equals("locked"))return Boolean.TRUE.equals(b.getBlockState().getValue(prop));return false;}
 static IItemHandler inventory(ServerPlayer p,BlockEntity b,ItemStack magnet){
  if(!SourceExtras.option(magnet,"CollectLocked") && (locked(b) || b instanceof BaseContainerBlockEntity base && !base.canOpen(p)))return null;
  if(b instanceof RandomizableContainerBlockEntity random)random.unpackLootTable(p);
  return b instanceof Container c?SourceExtras.direct(c):null;
 }
 static void finish(BlockEntity b){
  // 仅在库存完全收取后解锁；保留方块与其它状态，不触发召唤或复活逻辑。
  for(var prop:b.getBlockState().getProperties())if(prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty flag && prop.getName().equals("locked")){
   b.getLevel().setBlock(b.getBlockPos(),b.getBlockState().setValue(flag,false),3);b.setChanged();return;
  }
 }
}
