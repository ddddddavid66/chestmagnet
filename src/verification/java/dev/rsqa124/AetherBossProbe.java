package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraftforge.registries.ForgeRegistries;
final class AetherBossProbe {
 static BlockPos at;static ServerPlayer p;static ItemStack magnet;static Object state;static java.lang.reflect.Method work;static Object kind;
 static int phase,until;static java.util.Set<java.util.UUID> bosses;
 static boolean tick(net.minecraft.server.MinecraftServer server,int tick)throws Exception{
  var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new ResourceLocation("aether:the_aether")));
  Production128Repair.require(level!=null,"native Aether dimension available");
  if(at==null){
   at=Production128Repair.locateChest(level,"aether:gold_dungeon",null,"block:aether:treasure_chest",null);
   var cp=new ChunkPos(at);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced(cp.x+x,cp.z+z,true);
   p=Production128Repair.player(level,"AetherBossQA");p.setPos(at.getX()+1.5,at.getY(),at.getZ()+.5);level.addNewPlayer(p);
   magnet=Production128Repair.magnet();magnet.getOrCreateTagElement("ChestMagnet").putBoolean("ProtectBoss",true);
   var type=Class.forName("dev.chestmagnet.Engine$State");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);state=ctor.newInstance();work=Engine.class.getDeclaredMethod("work",ServerPlayer.class,ItemStack.class,type,long.class);work.setAccessible(true);
   var be=level.getBlockEntity(at);kind=be.getClass().getMethod("getKind").invoke(be);
   Production128Repair.require((boolean)be.getClass().getMethod("getLocked").invoke(be),"natural Aether chest initially locked");until=tick+100;return false;
  }
  var be=level.getBlockEntity(at);
  var alive=level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(at).inflate(64),e->String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(e.getType())).equals("aether:sun_spirit"));
  if(phase==0){
   if(alive.isEmpty()){Production128Repair.require(tick<until,"natural Sun Spirit not spawned");return false;}
   bosses=alive.stream().map(e->e.getUUID()).collect(java.util.stream.Collectors.toSet());phase=1;until=tick+40;
  }
  work.invoke(new Engine(),p,magnet,state,System.nanoTime()+2_000_000L);
  if(phase==1){
   Production128Repair.require((boolean)be.getClass().getMethod("getLocked").invoke(be),"magnet respected native lock");
   Production128Repair.require(alive.stream().map(e->e.getUUID()).collect(java.util.stream.Collectors.toSet()).equals(bosses),"native boss identities still present");
   Production128Repair.require(kind.equals(be.getClass().getMethod("getKind").invoke(be)),"Kind unchanged while protected");
   if(tick<until)return false;
   p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("aether:gold_dungeon_key"))));
   level.getBlockState(at).use(level,p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at),Direction.UP,at,false));
   Production128Repair.require(!(boolean)be.getClass().getMethod("getLocked").invoke(be),"native gold key unlock");phase=2;until=tick+100;return false;
  }
  var inv=Sources.inventory(p,be,magnet);boolean empty=inv!=null; if(inv!=null)for(int i=0;i<inv.getSlots();i++)if(!inv.getStackInSlot(i).isEmpty())empty=false;
  if(!empty){Production128Repair.require(tick<until,"unlocked Aether loot did not drain");return false;}
  Production128Repair.require(kind.equals(be.getClass().getMethod("getKind").invoke(be)),"Kind unchanged after draining");
  for(var bossId:bosses)Production128Repair.require(level.getEntity(bossId)!=null && level.getEntity(bossId).isAlive(),"native boss preserved after unlocked loot extraction");
  org.slf4j.LoggerFactory.getLogger("aether-boss-qa").info("AETHER_BOSS_PASS at={} Kind={} bosses={} native key unlocked and emptied",at,kind,bosses);return true;
 }
}
