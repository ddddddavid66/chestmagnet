package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.server.level.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
final class LootrStructureProbe {
 static BlockPos at;static ServerPlayer p;static ItemStack magnet;static Object job,before;static java.util.UUID uuid;
 static java.lang.reflect.Method collect;static final java.util.Map<String,java.lang.reflect.Field> fields=new java.util.HashMap<>();static int until;
 static Object get(String n)throws Exception{return fields.get(n).get(job);}
 static boolean tick(ServerLevel level,int tick)throws Exception{
  if(at==null){
   at=Production128Repair.locateChest(level,"minecraft:buried_treasure",new ResourceLocation("minecraft:chests/buried_treasure"),null,null);
   BuriedProbe.watched.add(at);var cp=new ChunkPos(at);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced(cp.x+x,cp.z+z,true);
   until=tick+500;return false;
  }
  if(job==null){
   if(!(level.getBlockEntity(at) instanceof ILootBlockEntity)){Production128Repair.require(tick<until,"Lootr conversion before job timed out");return false;}
   before=level.getBlockEntity(at);uuid=((ILootBlockEntity)before).getTileId();
   p=Production128Repair.player(level,"LootrStructureQA");p.setPos(at.getX()+1.5,at.getY(),at.getZ()+.5);magnet=Production128Repair.magnet();p.getInventory().setItem(0,magnet);
   var structure=level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(new ResourceLocation("minecraft:buried_treasure"));var start=level.getChunkAt(at).getStartForStructure(structure);Production128Repair.require(start!=null && start.isValid(),"actual structure instance");var cp=start.getChunkPos();level.getChunk(cp.x,cp.z);
   var result=new CompoundTag();result.putInt("Version",1);result.putString("State","FOUND");result.putUUID("Revision",java.util.UUID.randomUUID());result.putString("Dimension",level.dimension().location().toString());result.putString("Structure","minecraft:buried_treasure");result.putInt("StartX",cp.x);result.putInt("StartZ",cp.z);result.putLong("Anchor",at.asLong());CompassTargets.register(player->player==p?result:null);
   var type=Class.forName("dev.chestmagnet.StructureJobs");var jt=Class.forName("dev.chestmagnet.StructureJobs$Job");var create=type.getDeclaredMethod("create",ServerPlayer.class,ItemStack.class,CompoundTag.class,boolean.class);create.setAccessible(true);job=create.invoke(null,p,magnet,result,true);
   for(var f:jt.getDeclaredFields()){f.setAccessible(true);fields.put(f.getName(),f);}fields.get("viewing").setBoolean(job,false);
   var resume=type.getDeclaredMethod("resume",ServerPlayer.class,jt,ItemStack.class);resume.setAccessible(true);resume.invoke(null,p,job,magnet);collect=type.getDeclaredMethod("collect",ServerPlayer.class,jt,long.class);collect.setAccessible(true);until=tick+500;return false;
  }
  collect.invoke(null,p,job,System.nanoTime()+2_000_000L);
  Production128Repair.require(level.getBlockEntity(at)==before && uuid.equals(((ILootBlockEntity)before).getTileId()),"structure transfer must preserve Lootr block entity");
  if(!(boolean)get("complete")){Production128Repair.require(tick<until,"formal structure job timeout: "+get("status"));return false;}
  long received=p.getInventory().items.stream().filter(s->!s.is(ChestMagnet.MAGNET.get())).mapToLong(ItemStack::getCount).sum();
  Production128Repair.require(received>0 && received==((Number)get("processed")).longValue(),"source and destination accepted quantities match");
  Production128Repair.require(((java.util.Map<?,?>)get("tickets")).isEmpty(),"all structure tickets released");
  var inv=Sources.inventory(p,before,magnet);for(int i=0;i<inv.getSlots();i++)Production128Repair.require(inv.getStackInSlot(i).isEmpty(),"same personal loot exhausted");
  org.slf4j.LoggerFactory.getLogger("lootr-structure-qa").info("LOOTR_STRUCTURE_PASS source={} uuid={} processed={} chunks={} tickets=0 status={}",at,uuid,received,get("done"),get("status"));return true;
 }
}
