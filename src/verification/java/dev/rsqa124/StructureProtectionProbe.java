package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.server.level.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
final class StructureProtectionProbe {
 static Object job;static ServerPlayer p;static ItemStack magnet;static java.lang.reflect.Method collect,resume;static int phase,deadline;
 static long baseline;
 static final net.minecraftforge.items.ItemStackHandler ledger=new net.minecraftforge.items.ItemStackHandler(512);
 static java.util.Map<String,java.lang.reflect.Field> fields=new java.util.HashMap<>();
 static Object get(String n)throws Exception{return fields.get(n).get(job);}
 static boolean tick(ServerLevel level,int tick)throws Exception {
  if(job==null){
   var at=Production128Repair.locateChest(level,"goety:crypt",null,"block:goety:pithos",null);
   p=Production128Repair.player(level,"StructureProtect");p.setPos(at.getX()+1.5,at.getY(),at.getZ()+.5);
   magnet=Production128Repair.magnet();magnet.getOrCreateTagElement("ChestMagnet").putBoolean("ProtectBoss",true);p.getInventory().setItem(0,magnet);
   var structure=level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(new ResourceLocation("goety:crypt"));
   var start=level.structureManager().getStructureAt(at,structure);Production128Repair.require(start.isValid(),"real structure instance");var cp=start.getChunkPos();level.getChunk(cp.x,cp.z);level.setChunkForced(cp.x,cp.z,true);
   var result=new CompoundTag();result.putInt("Version",1);result.putString("State","FOUND");result.putUUID("Revision",java.util.UUID.randomUUID());result.putString("Dimension",level.dimension().location().toString());result.putString("Structure","goety:crypt");result.putInt("StartX",cp.x);result.putInt("StartZ",cp.z);result.putLong("Anchor",at.asLong());
   CompassTargets.register(player->player==p?result:null);
   var type=Class.forName("dev.chestmagnet.StructureJobs");var jt=Class.forName("dev.chestmagnet.StructureJobs$Job");
   var create=type.getDeclaredMethod("create",ServerPlayer.class,ItemStack.class,CompoundTag.class,boolean.class);create.setAccessible(true);job=create.invoke(null,p,magnet,result,true);
   for(var f:jt.getDeclaredFields()){f.setAccessible(true);fields.put(f.getName(),f);}fields.get("viewing").setBoolean(job,false);
   resume=type.getDeclaredMethod("resume",ServerPlayer.class,jt,ItemStack.class);resume.setAccessible(true);resume.invoke(null,p,job,magnet);
   collect=type.getDeclaredMethod("collect",ServerPlayer.class,jt,long.class);collect.setAccessible(true);deadline=tick+500;return false;
  }
  // 测试台账转存已到账物品，避免普通物品栏容量掩盖保护/继续状态；最终核对数量守恒。
  for(int i=1;i<36;i++){
   var stack=p.getInventory().getItem(i);if(stack.isEmpty())continue;
   var left=net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(ledger,stack,false);
   Production128Repair.require(left.isEmpty(),"QA ledger capacity");p.getInventory().setItem(i,left);
  }
  collect.invoke(null,p,job,System.nanoTime()+2_000_000L);
  if(!(boolean)get("complete")){Production128Repair.require(tick<deadline,"structure scan timed out: "+get("status"));return false;}
  var protectedKeys=(java.util.Set<?>)get("bossProtected");var result=(CompoundTag)get("result");
  String key=result.getString("Dimension")+"|"+result.getString("Structure")+"|"+new ChunkPos(result.getInt("StartX"),result.getInt("StartZ")).toLong();
  String remembered=p.getPersistentData().getCompound("ChestMagnetStructureHistoryV1").getCompound(key).getString("State");
  Production128Repair.require(((java.util.Map<?,?>)get("tickets")).isEmpty(),"completed/paused scan releases tickets");
  if(phase==0){
   Production128Repair.require(!protectedKeys.isEmpty() && remembered.equals("BLOCKED"),"protected rewards cannot become automatic COMPLETE");
   org.slf4j.LoggerFactory.getLogger("structure-policy-qa").info("STRUCTURE_PROTECTED_PASS count={} status={}",protectedKeys.size(),get("status"));
   // 本测试只验证暂停/继续策略；真实召唤死亡解锁由独立自然墓穴用例验证。
   for(var keyObject:java.util.List.copyOf(protectedKeys)){
    var text=keyObject.toString();var pos=BlockPos.of(Long.parseLong(text.substring(text.lastIndexOf(':')+1)));var be=level.getBlockEntity(pos);if(be==null)continue;
    if(be.getClass().getName().endsWith("PithosBlockEntity"))be.getClass().getMethod("unlock").invoke(be);
    else for(var prop:be.getBlockState().getProperties())if(prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty flag && prop.getName().equals("locked"))level.setBlockAndUpdate(pos,be.getBlockState().setValue(flag,false));
   }
   baseline=total();resume.invoke(null,p,job,magnet);phase=1;deadline=tick+500;return false;
  }
  Production128Repair.require(protectedKeys.isEmpty() && remembered.equals("COMPLETE"),"continued unlocked sources complete without stale protection count");
  Production128Repair.require(total()-baseline==((Number)get("processed")).longValue(),"source/destination counts conserved across resumed scan");
  net.minecraft.nbt.NbtIo.writeCompressed(ledger.serializeNBT(),new java.io.File("structure-protection-delivery.nbt"));
  org.slf4j.LoggerFactory.getLogger("structure-policy-qa").info("STRUCTURE_RESUME_PASS processed={} tickets=0 protected=0 accounted={}",get("processed"),total()-baseline);return true;
 }
 static long total(){long n=0;for(int i=0;i<ledger.getSlots();i++)n+=ledger.getStackInSlot(i).getCount();for(int i=1;i<36;i++)n+=p.getInventory().getItem(i).getCount();return n;}
}
