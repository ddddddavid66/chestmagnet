package dev.rsqa124;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.nbt.CompoundTag;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import net.minecraftforge.items.IItemHandlerModifiable;
final class InkProbe {
 static ItemTask task,consume;static int stage,age;static Object scan;static java.lang.reflect.Method step;
 static ItemStack sample;
 static Chapter chapter;static long queuedAt;static final java.util.List<Long> queueSlices=new java.util.ArrayList<>();
 static boolean tick(ServerPlayer p,INetwork rs,DimensionsNet net,IItemHandlerModifiable bag,ItemStack magnet)throws ReflectiveOperationException {
  if(stage==6)return InkChainProbe.tick(p,rs,net,magnet);
  var file=ServerQuestFile.INSTANCE;var team=TeamData.get(p);
  if(stage==0){
   sample=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("irons_spellbooks:legendary_ink")));
   if(sample.isEmpty())throw new IllegalStateException("real legendary ink missing");
   var group=file.getDefaultChapterGroup();chapter=new Chapter(128900L,file,group,"ink128");group.addChapter(chapter);
   task=create(chapter,128910,false);consume=create(chapter,128920,true);file.refreshIDMap();file.clearCachedData();
   var cls=Class.forName("dev.chestmagnet.QuestDetection$Scan");var ctor=cls.getDeclaredConstructor();ctor.setAccessible(true);scan=ctor.newInstance();
   step=Class.forName("dev.chestmagnet.QuestCompat").getDeclaredMethod("step",ServerPlayer.class,cls,long.class);step.setAccessible(true);
   p.getInventory().setItem(10,sample.copyWithCount(3));stage=1;
  }
  // 直接运行磁铁自身匹配/读取管线，来源数据全部为原生库存；不调用FTB拾取或制作事件。
  long began=System.nanoTime();
  for(int i=0;i<(stage==5?1:20);i++) if (!((boolean)step.invoke(null,p,scan,System.nanoTime()+500_000L))) {
   var ctor=scan.getClass().getDeclaredConstructor();ctor.setAccessible(true);scan=ctor.newInstance();
  }
  if(stage==5)queueSlices.add(System.nanoTime()-began);
  long expected=new long[]{0,3,5,7,9,9}[stage];
  if(team.getProgress(task)<expected){if(++age>200)throw new IllegalStateException("ink phase "+stage+" progress="+team.getProgress(task));return false;}
  if(team.getProgress(task)!=expected || team.getProgress(consume)!=0)throw new IllegalStateException("incorrect ink amount/consume");
  org.slf4j.LoggerFactory.getLogger("ink-qa").info("INK_PASS phase={} amount={} consume=0",stage,expected);
  age=0;
  if(stage==1)bag.setStackInSlot(20,sample.copyWithCount(5));
  if(stage==2)rs.insertItem(sample,7,Action.PERFORM);
  if(stage==3)net.getUnifiedStorage().insert(new ItemStackKey(sample),9,false);
  if(stage==4){
   if(p.getInventory().getItem(10).getCount()!=3 || bag.getStackInSlot(20).getCount()!=5)throw new IllegalStateException("detection consumed ink");
   p.getInventory().setItem(10,ItemStack.EMPTY);bag.setStackInSlot(20,ItemStack.EMPTY);
   org.slf4j.LoggerFactory.getLogger("ink-qa").info("INK_ALL_PASS inventory/backpack/RS/Beyond actual stock, max not sum, no consumption");
   for(int i=0;i<4467;i++)create(chapter,1_500_000+i*2,true);
   task=create(chapter,1_520_000,false);file.refreshIDMap();file.clearCachedData();queuedAt=System.nanoTime();
  }
  if(stage==5){
   queueSlices.sort(Long::compare);
   org.slf4j.LoggerFactory.getLogger("ink-qa").info("INK_QUEUE_PASS tasks=4467 tailProgress={} elapsed_ms={} slices={} p95_us={}",team.getProgress(task),(System.nanoTime()-queuedAt)/1_000_000,queueSlices.size(),queueSlices.get((queueSlices.size()-1)*95/100)/1000);
   stage=6;return false;
  }
  // 下一轮从任务队首开始；只重建游标，不依赖原生FTB检测。
  var cls=scan.getClass();var ctor=cls.getDeclaredConstructor();ctor.setAccessible(true);scan=ctor.newInstance();stage++;return false;
 }
 static void emptyTasks(ServerPlayer p){
  if(!ServerQuestFile.INSTANCE.getSubmitTasks().isEmpty())throw new IllegalStateException("empty-task fixture is not empty");
  QuestTiming.readers=0;QuestTiming.countReaders=true;
  try{
   var cls=Class.forName("dev.chestmagnet.QuestDetection$Scan");var ctor=cls.getDeclaredConstructor();ctor.setAccessible(true);
   var method=Class.forName("dev.chestmagnet.QuestCompat").getDeclaredMethod("step",ServerPlayer.class,cls,long.class);method.setAccessible(true);
   for(int i=0;i<3;i++)if((boolean)method.invoke(null,p,ctor.newInstance(),System.nanoTime()+500_000L))throw new IllegalStateException("empty tasks should end immediately");
   if(QuestTiming.readers!=0)throw new IllegalStateException("empty task list read stock");
   NearbyProductionChecks.LOG.info("INK_EMPTY_TASKS_PASS tasks=0 readers=0 calls=3");
  }catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}finally{QuestTiming.countReaders=false;}
 }
 static ItemTask create(Chapter chapter,long id,boolean consume){
  var quest=new Quest(id,chapter);chapter.addQuest(quest);var task=new ItemTask(id+1,quest);
  var data=new CompoundTag();dev.ftb.mods.ftbquests.util.NBTUtils.write(data,"item",sample);data.putLong("count",64);data.putBoolean("consume_items",consume);task.readData(data);quest.addTask(task);return task;
 }
}
