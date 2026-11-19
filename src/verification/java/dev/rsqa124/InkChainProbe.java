package dev.rsqa124;

import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;

/** 全链路只放置测试来源与设置，收取、FTB调度都由正式模组自己的tick执行。 */
public final class InkChainProbe {
  public static ItemTask watched;
  public static TeamData team;
  private static int mode, age;
  private static boolean prepared;
  private static BarrelBlockEntity barrel;
  private static ItemStack upgrade;
  private static boolean oldEnabled;
  private static int oldMode;
  private static final int[] MODE={0,2,1,3}, QUANTITY={11,13,17,19}, EXPECTED={11,13,24,28};
  static boolean tick(ServerPlayer p, INetwork rs, DimensionsNet net, ItemStack magnet) {
    var config=magnet.getOrCreateTagElement("ChestMagnet");
    var wrapper=p.getInventory().getItem(4).getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).orElseThrow(IllegalStateException::new);
    if (!prepared) {
      if(mode==0){
        oldEnabled=config.getBoolean("Enabled");oldMode=config.getInt("Mode");
        upgrade=wrapper.getUpgradeHandler().getStackInSlot(0).copy();
        wrapper.getUpgradeHandler().setStackInSlot(0,ItemStack.EMPTY);
      }
      team=TeamData.get(p);
      watched=InkProbe.create(InkProbe.chapter,1_530_000+mode*2,false);
      QuestTiming.watchedProgress = () -> team.getProgress(watched);
      ServerQuestFile.INSTANCE.refreshIDMap();ServerQuestFile.INSTANCE.clearCachedData();
      var at=p.blockPosition().south(2);
      p.serverLevel().setBlockAndUpdate(at,Blocks.BARREL.defaultBlockState());
      barrel=(BarrelBlockEntity)p.serverLevel().getBlockEntity(at);
      barrel.setItem(0,InkProbe.sample.copyWithCount(QUANTITY[mode]));barrel.setChanged();
      config.putBoolean("Enabled",true);config.putBoolean("QuestDetection",true);config.putInt("Mode",MODE[mode]);
      prepared=true;age=0;return false;
    }
    if(++age>300)throw new IllegalStateException("ink native chain timed out mode="+MODE[mode]+" source="+barrel.getItem(0)+" task="+team.getProgress(watched));
    if(!barrel.isEmpty() || team.getProgress(watched)<EXPECTED[mode])return false;
    if(team.getProgress(watched)!=EXPECTED[mode])throw new IllegalStateException("native chain overcount");
    long stock=switch(MODE[mode]){
      case 0 -> p.getInventory().countItem(InkProbe.sample.getItem());
      case 1 -> rs.getItemStorageCache().getList().getStacks(InkProbe.sample).stream().mapToLong(x->x.getStack().getCount()).sum();
      case 3 -> net.getUnifiedStorage().getStackByKey(new ItemStackKey(InkProbe.sample)).amount();
      default -> {long n=0;var inv=wrapper.getInventoryHandler();for(int i=0;i<inv.getSlots();i++)if(inv.getStackInSlot(i).is(InkProbe.sample.getItem()))n+=inv.getStackInSlot(i).getCount();yield n;}
    };
    if(stock!=EXPECTED[mode])throw new IllegalStateException("source/destination mismatch actual="+stock);
    NearbyProductionChecks.LOG.info("INK_CHAIN_PASS mode={} source=0 stock={} task={} ticks={}",MODE[mode],stock,team.getProgress(watched),age);
    p.serverLevel().removeBlock(barrel.getBlockPos(),false);prepared=false;
    if(++mode<4)return false;
    wrapper.getUpgradeHandler().setStackInSlot(0,upgrade);
    config.putBoolean("Enabled",oldEnabled);config.putInt("Mode",oldMode);
    QuestTiming.watchedProgress=null;watched=null;NearbyProductionChecks.LOG.info("INK_NATIVE_ALL_PASS four destinations, actual Engine + QuestDetection ticks");
    return true;
  }
}
