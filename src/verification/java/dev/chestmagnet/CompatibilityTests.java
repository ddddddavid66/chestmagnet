package dev.chestmagnet;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import java.util.UUID;
@GameTestHolder("chestmagnet") @PrefixGameTestTemplate(false)
public class CompatibilityTests {
 @GameTest(template="empty") public static void containers(GameTestHelper h){
  var p=FakePlayerFactory.getMinecraft(h.getLevel());var m=new ItemStack(ChestMagnet.MAGNET.get());
  for(String id:java.util.List.of("minecraft:barrel","minecraft:brewing_stand","goety:lofty_chest","goety:crypt_chest","goety:pithos")){
   var b=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));if(b==null||b==Blocks.AIR){h.assertTrue(!id.startsWith("minecraft:"),"required vanilla block");continue;}
   h.setBlock(1,1,1,b);var be=h.getBlockEntity(new BlockPos(1,1,1));h.assertTrue(Sources.eligible(be),id+" eligible");
   var inv=Sources.inventory(p,be,m);h.assertTrue(inv!=null,id+" inventory");
   ((net.minecraft.world.Container)be).setItem(0,new ItemStack(Items.DIAMOND,7));
   var taken=inv.extractItem(0,3,false);h.assertTrue(taken.getCount()==3 && inv.getStackInSlot(0).getCount()==4,id+" partial extraction");
   inv.extractItem(0,64,false);h.assertTrue(Sources.inventory(p,be,m).getStackInSlot(0).isEmpty(),id+" no duplicate");
   if(ChurchContainers.known(be)&&ChurchContainers.locked(be)){Data.tag(m).putBoolean("CollectLocked",false);h.assertTrue(Sources.inventory(p,be,m)==null,"locked opt-out");Data.tag(m).putBoolean("CollectLocked",true);ChurchContainers.finish(be);h.assertTrue(!ChurchContainers.locked(be),"finish unlock");}
  }h.succeed();
 }
 @GameTest(template="empty") public static void snapshots(GameTestHelper h){
  var p=FakePlayerFactory.getMinecraft(h.getLevel());if(CompassTargets.apiPresent()){h.succeed();return;}
  var token=UUID.randomUUID();CompassTargets.publish(p,token,"FOUND",BlockPos.ZERO,new ResourceLocation("minecraft:stronghold"),new ChunkPos(3,7));
  var s=CompassTargets.snapshot(p);h.assertTrue(s.getUUID("Revision").equals(token)&&s.getInt("StartZ")==7,"snapshot exact start");
  CompassTargets.state(p,"CANCELLED");h.assertTrue(CompassTargets.snapshot(p).getString("State").equals("CANCELLED"),"cancel clears found");h.succeed();
 }
 @GameTest(template="empty") public static void luck(GameTestHelper h){
  var p=FakePlayerFactory.getMinecraft(h.getLevel());p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.LUCK).setBaseValue(20);
  h.setBlock(1,1,1,Blocks.BARREL);h.setBlock(2,1,1,Blocks.BARREL);
  var nativeBox=(RandomizableContainerBlockEntity)h.getBlockEntity(new BlockPos(1,1,1));var magnetBox=(RandomizableContainerBlockEntity)h.getBlockEntity(new BlockPos(2,1,1));
  var table=new ResourceLocation("chestmagnet:luck123");nativeBox.setLootTable(table,12345);magnetBox.setLootTable(table,12345);
  nativeBox.createMenu(1,p.getInventory(),p);var inv=Sources.inventory(p,magnetBox);
  h.assertTrue(nativeBox.countItem(Items.DIAMOND)==1 && magnetBox.countItem(Items.DIAMOND)==1,"native and magnet both use player luck");
  inv.extractItem(0,64,false);int before=magnetBox.countItem(Items.DIAMOND);Sources.inventory(p,magnetBox);h.assertTrue(before==magnetBox.countItem(Items.DIAMOND),"no reroll");p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.LUCK).setBaseValue(0);h.succeed();
 }
}
