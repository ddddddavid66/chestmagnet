package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.registries.ForgeRegistries;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
public final class BuriedProbe {
 static BlockPos early,live;
 static ServerPlayer p;
 static ItemStack magnet;
 static Object engine,state;
 static java.lang.reflect.Method work;
 static int settle;
 static BlockEntity identity;
 static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("buried-qa");
 static boolean tick(ServerLevel level,String mode,int ticks)throws Exception {
  boolean off=mode.endsWith("off"), baseline=mode.endsWith("baseline");
  if(early==null){
   var table=new ResourceLocation("minecraft:chests/buried_treasure");
   early=Production128Repair.locateChest(level,"minecraft:buried_treasure",table,null,null);
   live=Production128Repair.locateChest(level,"minecraft:buried_treasure",table,null,early,early.offset(3000,0,3000));
   watched.add(early);watched.add(live);
   p=Production128Repair.player(level,"BuriedQA");magnet=Production128Repair.magnet();
   var type=Class.forName("dev.chestmagnet.Engine$State");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);state=ctor.newInstance();
   engine=new Engine();work=Engine.class.getDeclaredMethod("work",ServerPlayer.class,ItemStack.class,type,long.class);work.setAccessible(true);
   p.setPos(early.getX()+.5,early.getY(),early.getZ()+.5);
   if(!off) for(int i=0;i<30;i++)work.invoke(engine,p,magnet,state,Long.MAX_VALUE);
   var be=level.getBlockEntity(early);
   LOG.info("BURIED_EARLY mode={} pos={} block={} table={} items={}",mode,early,id(level,early),be.saveWithoutMetadata().getString("LootTable"),received());
   for(var pos:java.util.List.of(early,live)){var cp=new ChunkPos(pos);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced(cp.x+x,cp.z+z,true);}
   return false;
  }
  if(identity==null){
   if(!(level.getBlockEntity(live) instanceof ILootBlockEntity)){Production128Repair.require(ticks<1100,"native Lootr conversion timed out");return false;}
   identity=level.getBlockEntity(live);p.setPos(live.getX()+.5,live.getY(),live.getZ()+.5);
   var type=state.getClass();var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);state=ctor.newInstance();
   if(!off)for(int i=0;i<30;i++)work.invoke(engine,p,magnet,state,Long.MAX_VALUE);
   Production128Repair.require(level.getBlockEntity(live)==identity,"already converted Lootr replaced during formal nearby work");
   LOG.info("BURIED_LIVE mode={} pos={} block={} uuid={} items={}",mode,live,id(level,live),((ILootBlockEntity)identity).getTileId(),received());
   if(!level.getServer().getPlayerList().getPlayers().isEmpty()){
    level.getServer().getPlayerList().getPlayers().get(0).teleportTo(level,live.getX()+2.5,live.getY(),live.getZ()+.5,0,0);
    BuriedClientCheck.pos=live;BuriedClientCheck.expected=id(level,live);
   }
   settle=ticks+60;return false;
  }
  if(ticks<settle)return false;
  Production128Repair.require(level.getBlockEntity(live)==identity,"Lootr changed after 60 ticks");
  Production128Repair.require(!baseline || id(level,early).equals("minecraft:chest"),"baseline conversion race");
  Production128Repair.require(baseline || level.getBlockEntity(early) instanceof ILootBlockEntity,"candidate/off should allow conversion");
  if(!level.getServer().getPlayerList().getPlayers().isEmpty() && !BuriedClientCheck.confirmed){
   Production128Repair.require(ticks<settle+300,"client/server block identity mismatch or chunk unavailable: "+BuriedClientCheck.last);return false;
  }
  var saved=new net.minecraft.nbt.CompoundTag();saved.putLong("Early",early.asLong());saved.putLong("Live",live.asLong());
  saved.putString("Mode",mode);saved.putUUID("Player",p.getUUID());saved.putUUID("Lootr",((ILootBlockEntity)identity).getTileId());
  net.minecraft.nbt.NbtIo.writeCompressed(saved,new java.io.File("buried-probe.nbt"));
  LOG.info("BURIED_PASS mode={} early={} live={}",mode,id(level,early),id(level,live));return true;
 }
 static void reopen(ServerLevel level)throws Exception{
  var data=net.minecraft.nbt.NbtIo.readCompressed(new java.io.File("buried-probe.nbt"));
  var early=BlockPos.of(data.getLong("Early"));var live=BlockPos.of(data.getLong("Live"));level.getChunkAt(early);level.getChunkAt(live);
  var be=level.getBlockEntity(live);
  Production128Repair.require(be instanceof ILootBlockEntity && data.getUUID("Lootr").equals(((ILootBlockEntity)be).getTileId()),"same Lootr identity after saved restart");
  Production128Repair.require(data.getString("Mode").endsWith("baseline") ? id(level,early).equals("minecraft:chest") : level.getBlockEntity(early) instanceof ILootBlockEntity,"early source restart identity");
  if(!data.getString("Mode").endsWith("off")){
   var p=net.minecraftforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(data.getUUID("Player"),"BuriedQA"));
   var inv=Sources.inventory(p,be,Production128Repair.magnet());Production128Repair.require(inv!=null,"saved personal inventory available");
   for(int i=0;i<inv.getSlots();i++)Production128Repair.require(inv.getStackInSlot(i).isEmpty(),"loot not regenerated after restart");
  }
  LOG.info("BURIED_REOPEN_PASS mode={} early={} live={}",data.getString("Mode"),id(level,early),id(level,live));
 }
 public static final java.util.Set<BlockPos> watched=new java.util.HashSet<>();
 public static void trace(BlockPos pos,String previous,String next){if(watched.contains(pos))LOG.info("BURIED_WRITE {} {} -> {}",pos,previous,next,new Exception("QA-only writer"));}
 static String id(ServerLevel l,BlockPos p){return String.valueOf(ForgeRegistries.BLOCKS.getKey(l.getBlockState(p).getBlock()));}
 static int received(){return p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();}
}
