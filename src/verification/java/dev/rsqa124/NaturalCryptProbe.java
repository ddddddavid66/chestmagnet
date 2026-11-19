package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.items.IItemHandler;
final class NaturalCryptProbe {
 static BlockPos at;static ServerPlayer player;static ItemStack magnet;static Object state;static java.lang.reflect.Method work;
 static int phase,until;static boolean killed;
 static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("crypt-qa");
 static boolean tick(ServerLevel level,int tick)throws Exception {
  if(at==null){
   at=Production128Repair.locateChest(level,"goety:crypt",null,"block:goety:pithos",null);
   var cp=new ChunkPos(at);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced(cp.x+x,cp.z+z,true);
   player=Production128Repair.player(level,"NaturalCrypt");player.setPos(at.getX()+1.5,at.getY(),at.getZ()+.5);
   magnet=Production128Repair.magnet();magnet.getOrCreateTagElement("ChestMagnet").putBoolean("ProtectBoss",true);
   var type=Class.forName("dev.chestmagnet.Engine$State");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);state=ctor.newInstance();
   work=Engine.class.getDeclaredMethod("work",ServerPlayer.class,ItemStack.class,type,long.class);work.setAccessible(true);
   Production128Repair.require(locked(level),"natural crypt must start locked");until=tick+20;return false;
  }
  work.invoke(new Engine(),player,magnet,state,System.nanoTime()+2_000_000L);
  if(phase==0){
   Production128Repair.require(locked(level),"magnet changed natural summon lock");
   if(tick<until)return false;
   level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
   level.getBlockState(at).use(level,player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at),Direction.UP,at,false));
   phase=1;until=tick+500;return false;
  }
  if(phase==1){
   var bosses=level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(at).inflate(48),e->String.valueOf(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())).equals("goety:skull_lord"));
   if(!killed && !bosses.isEmpty()) {
    Production128Repair.require(locked(level),"boss fight must remain protected");
    var boss=bosses.get(0);boolean hit=boss.hurt(level.damageSources().genericKill(),Float.MAX_VALUE);
    Production128Repair.require(hit && boss.isDeadOrDying(),"native fatal damage must kill boss");killed=true;
    LOG.info("CRYPT_NATIVE_BOSS_KILLED at={} type={}",at,boss.getType());
   }
   if(!killed || locked(level)){Production128Repair.require(tick<until,"native summon/death/unlock timed out");return false;}
   phase=2;until=tick+200;return false;
  }
  var inv=Sources.inventory(player,level.getBlockEntity(at),magnet);
  boolean empty=inv!=null; if(inv!=null)for(int i=0;i<inv.getSlots();i++)if(!inv.getStackInSlot(i).isEmpty())empty=false;
  if(!empty){Production128Repair.require(tick<until,"native unlocked source failed to drain");return false;}
  var saved=new CompoundTag();saved.putLong("Pos",at.asLong());saved.put("Magnet",magnet.save(new CompoundTag()));
  NbtIo.writeCompressed(saved,new java.io.File("crypt-probe.nbt"));
  LOG.info("CRYPT_NATURAL_PASS at={} locked=false emptied after real boss death",at);return true;
 }
 static boolean locked(ServerLevel level){for(var e:level.getBlockState(at).getValues().entrySet())if(e.getKey().getName().equals("locked"))return Boolean.TRUE.equals(e.getValue());return false;}
 static void reopen(ServerLevel level)throws Exception{
  var saved=NbtIo.readCompressed(new java.io.File("crypt-probe.nbt"));at=BlockPos.of(saved.getLong("Pos"));level.getChunkAt(at);
  var p=Production128Repair.player(level,"CryptReload");var m=ItemStack.of(saved.getCompound("Magnet"));
  Production128Repair.require(m.getOrCreateTagElement("ChestMagnet").getBoolean("ProtectBoss") && !locked(level),"saved protection and native unlock survive restart");
  var inv=Sources.inventory(p,level.getBlockEntity(at),m);Production128Repair.require(inv!=null,"source remains after reload");
  for(int i=0;i<inv.getSlots();i++)Production128Repair.require(inv.getStackInSlot(i).isEmpty(),"no reroll on restart");
  LOG.info("CRYPT_REOPEN_PASS {} native unlock and empty loot persisted",at);
 }
}
