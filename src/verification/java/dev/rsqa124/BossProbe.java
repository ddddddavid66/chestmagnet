package dev.rsqa124;
import dev.chestmagnet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
final class BossProbe {
 static void run(net.minecraft.server.level.ServerLevel level) throws Exception {
  var p=Production128Repair.player(level,"BossQA");
  var magnet=Production128Repair.magnet();
  var at=new BlockPos(6,110,6);level.setBlockAndUpdate(at.below(),Blocks.STONE.defaultBlockState());
  for(String id:java.util.List.of("goety:pithos","goety:crypt_chest")) {
   var block=ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
   Production128Repair.require(block!=Blocks.AIR,"actual Goety block installed");
   var state=block.defaultBlockState();
   for(var prop:state.getProperties()) if(prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty flag && prop.getName().equals("locked"))state=state.setValue(flag,true);
   level.setBlockAndUpdate(at,state);var be=level.getBlockEntity(at);
   var random=(net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity)be;
   random.setLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"),3);
   var before=be.saveWithoutMetadata();
   magnet.getOrCreateTagElement("ChestMagnet").putBoolean("ProtectBoss",true);
   Production128Repair.require(Sources.inventory(p,be,magnet)==null && before.equals(be.saveWithoutMetadata()),"protected without generating/changing source "+id);
   if(id.endsWith(":pithos")) {
    level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
    state.use(level, p, InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at),Direction.UP,at,false));
    Production128Repair.require(level.getBlockState(at).getValues().entrySet().stream().anyMatch(e->e.getKey().getName().equals("triggered") && Boolean.TRUE.equals(e.getValue())),"native summon branch still works");
    Production128Repair.require(Sources.inventory(p,be,magnet)==null,"battle still protected");
    be.getClass().getMethod("unlock").invoke(be);
   } else {
    for(var prop:state.getProperties()) if(prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty flag && prop.getName().equals("locked"))level.setBlockAndUpdate(at,state.setValue(flag,false));
   }
   Production128Repair.require(Sources.inventory(p,level.getBlockEntity(at),magnet)!=null,"native unlocked source accessible "+id);
   level.removeBlock(at,false);
  }
  org.slf4j.LoggerFactory.getLogger("boss-qa").info("BOSS_PASS protected native summon, locked crypt, unlocked access; defaults remain false");
 }
}
