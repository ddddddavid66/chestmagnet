package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
public final class OriginalBuriedProbe {
 public static volatile BlockPos at;
 public static volatile boolean seen;
 public static volatile int remaining=-1;
 static ServerPlayer player;static ItemStack magnet;static int phase,until;static java.util.UUID uuid;
 static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("original-buried-qa");
 static boolean tick(ServerLevel level,int tick)throws Exception {
  var players=level.getServer().getPlayerList().getPlayers();if(players.isEmpty())return false;
  if(phase==0){
   player=players.get(0);
   // 测试传送可能把角色放在埋藏箱旁的实心方块中；只在副本中防止环境伤害干扰收取。
   player.getAbilities().invulnerable=true;player.onUpdateAbilities();
   for(int i=0;i<36;i++){var s=player.getInventory().getItem(i);if(s.is(ChestMagnet.MAGNET.get())){magnet=s;break;}}
   if(magnet==null && player.getOffhandItem().is(ChestMagnet.MAGNET.get()))magnet=player.getOffhandItem();
   Production128Repair.require(magnet!=null,"original carried magnet not found");
   Data.tag(magnet).putBoolean("Enabled",false);
   at=Production128Repair.locateChest(level,"minecraft:buried_treasure",new ResourceLocation("minecraft:chests/buried_treasure"),null,null,new BlockPos(12000,70,12000));
   BuriedProbe.watched.add(at);var cp=new ChunkPos(at);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced(cp.x+x,cp.z+z,true);
   player.teleportTo(level,at.getX()+1.5,at.getY(),at.getZ()+.5,0,0);
   LOG.info("ORIGINAL_PROFILE seed={} UUID={} mode={} source={} block={} tasks={}",level.getSeed(),player.getUUID(),Data.mode(magnet),at,BuriedProbe.id(level,at),Data.questDetection(Data.tag(magnet)));
   phase=1;until=tick+800;return false;
  }
  var be=level.getBlockEntity(at);
  if(phase==1){
   if(!(be instanceof ILootBlockEntity)){Production128Repair.require(tick<until,"original-world Lootr conversion timed out");return false;}
   uuid=((ILootBlockEntity)be).getTileId();BuriedClientCheck.pos=at;BuriedClientCheck.expected=BuriedProbe.id(level,at);
   LOG.info("ORIGINAL_BEFORE source={} block={} uuid={}",at,BuriedProbe.id(level,at),uuid);
   Engine.toggle(player,magnet);phase=2;until=tick+500;return false;
  }
  Production128Repair.require(be instanceof ILootBlockEntity && uuid.equals(((ILootBlockEntity)be).getTileId()),"actual Lootr reverse replacement at "+at+" block="+BuriedProbe.id(level,at));
  if(phase==2){
   if(!seen || remaining!=0){Production128Repair.require(tick<until,"original native collection not completed: "+Engine.status(player)+" seen="+seen+" remaining="+remaining);return false;}
   var type=Class.forName("dev.chestmagnet.Engine");var f=type.getDeclaredField("STATES");f.setAccessible(true);var state=((java.util.Map<?,?>)f.get(null)).get(player.getUUID());
   Production128Repair.require(state!=null,"real Engine state exists");var counter=state.getClass().getDeclaredField("processed");counter.setAccessible(true);
   Production128Repair.require(counter.getLong(state)>0,"native collection actually accepted loot");
   LOG.info("ORIGINAL_COLLECTED source={} block={} uuid={} mode={} processed={}",at,BuriedProbe.id(level,at),uuid,Data.mode(magnet),counter.getLong(state));phase=3;until=tick+60;return false;
  }
  if(phase==3 && tick<until)return false;
  if(Boolean.getBoolean("chestmagnet.originalScale") && !OriginalQuestScaleProbe.tick(player,magnet,tick)){phase=4;return false;}
  Production128Repair.require(BuriedClientCheck.confirmed,"client matches actual server block");
  LOG.info("ORIGINAL_BURIED_PASS source={} block={} uuid={} real player/native ticks preserved",at,BuriedProbe.id(level,at),uuid);return true;
 }
}
