package dev.rsqa124;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="rsqa124",value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class BuriedClientCheck {
 public static volatile net.minecraft.core.BlockPos pos;
 public static volatile String expected,last="not loaded";
 public static volatile boolean confirmed;
 @net.minecraftforge.eventbus.api.SubscribeEvent
 public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent e){
  if(e.phase!=net.minecraftforge.event.TickEvent.Phase.END)return;
  var mc=net.minecraft.client.Minecraft.getInstance();
  if(System.getProperty("chestmagnet.repair128", "").startsWith("buried") && Production128Repair.done){
   var server=mc.getSingleplayerServer();if(server==null || server.isStopped())mc.stop();return;
  }
  if(pos==null || confirmed)return;if(mc.level==null || !mc.level.hasChunkAt(pos))return;
  last=String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(mc.level.getBlockState(pos).getBlock()));
  if(last.equals(expected)){
   confirmed=true;org.slf4j.LoggerFactory.getLogger("buried-client-qa").info("BURIED_CLIENT_PASS pos={} server={} client={}",pos,expected,last);
  }
 }
}
