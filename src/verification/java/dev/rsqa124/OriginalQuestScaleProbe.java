package dev.rsqa124;
import dev.chestmagnet.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
final class OriginalQuestScaleProbe {
 static ItemTask task;static int lastIndex,deadline;static long began;
 static boolean present,oldValue;static BarrelBlockEntity barrel;
 static boolean tick(ServerPlayer p,ItemStack magnet,int tick)throws Exception {
  var tag=Data.tag(magnet);var file=ServerQuestFile.INSTANCE;var team=TeamData.get(p);
  if(task==null){
   int original=file.getSubmitTasks().size();var groups=file.getChapterGroups();var group=groups.isEmpty()?file.getDefaultChapterGroup():groups.get(groups.size()-1);var chapter=new Chapter(0x7AAA010000000001L,file,group,"original-scale-qa");group.addChapter(chapter);
   var quest=new Quest(0x7AAA010000000002L,chapter);chapter.addQuest(quest);task=new ItemTask(0x7AAA010000000003L,quest);
   var data=new CompoundTag();dev.ftb.mods.ftbquests.util.NBTUtils.write(data,"item",new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("irons_spellbooks:legendary_ink"))));
   data.putLong("count",64);data.putBoolean("consume_items",false);task.readData(data);quest.addTask(task);file.refreshIDMap();file.clearCachedData();lastIndex=file.getSubmitTasks().indexOf(task);
   Production128Repair.require(lastIndex>=original,"task really appended behind original definitions");
   var at=p.blockPosition().north(2);Production128Repair.require(p.serverLevel().getBlockEntity(at)==null,"do not replace existing container for ink fixture");
   p.serverLevel().setBlockAndUpdate(at,Blocks.BARREL.defaultBlockState());barrel=(BarrelBlockEntity)p.serverLevel().getBlockEntity(at);barrel.setItem(0,task.getItemStack().copyWithCount(1));barrel.setChanged();
   present=tag.contains("QuestDetection");oldValue=Data.questDetection(tag);tag.putBoolean("QuestDetection",true);
   var remove=Class.forName("dev.chestmagnet.QuestDetection").getDeclaredMethod("remove",java.util.UUID.class);remove.setAccessible(true);remove.invoke(null,p.getUUID());
   QuestTiming.commits=0;QuestTiming.watchedProgress=()->team.getProgress(task);QuestTiming.measureOriginal=true;
   began=System.nanoTime();deadline=tick+1200;
   org.slf4j.LoggerFactory.getLogger("original-ftb-qa").info("ORIGINAL_FTB_BEGIN originalTasks={} tail={} nativeTeamProgress retained",original,lastIndex);return false;
  }
  var scansField=Class.forName("dev.chestmagnet.QuestDetection").getDeclaredField("SCANS");scansField.setAccessible(true);var scan=((java.util.Map<?,?>)scansField.get(null)).get(p.getUUID());
  int cursor=-1,dest=-1;Object current=null;
  if(scan!=null){var cls=scan.getClass();var f=cls.getDeclaredField("task");f.setAccessible(true);cursor=f.getInt(scan);f=cls.getDeclaredField("destination");f.setAccessible(true);dest=f.getInt(scan);f=cls.getDeclaredField("current");f.setAccessible(true);current=f.get(scan);}
  boolean visited=cursor>lastIndex || current==task && dest>=3;
  if(QuestTiming.commits==0 || !barrel.isEmpty() || team.getProgress(task)<1){Production128Repair.require(tick<deadline,"original task queue timed out cursor="+cursor+" target="+lastIndex+" progress="+team.getProgress(task));return false;}
  org.slf4j.LoggerFactory.getLogger("original-ftb-qa").info("ORIGINAL_FTB_SCALE_PASS tasks={} cursor={} dest={} elapsed_ms={} progress={} sourceEmpty=true; change observed within production scan",file.getSubmitTasks().size(),cursor,dest,(System.nanoTime()-began)/1_000_000,team.getProgress(task));
  QuestTiming.measureOriginal=false;QuestTiming.watchedProgress=null;QuestTiming.report();
  org.slf4j.LoggerFactory.getLogger("original-ftb-qa").info("ORIGINAL_FTB_MAGNET_COMMITS {}",QuestTiming.commits);
  if(present)tag.putBoolean("QuestDetection",oldValue);else tag.remove("QuestDetection");p.serverLevel().removeBlock(barrel.getBlockPos(),false);return true;
 }
}
