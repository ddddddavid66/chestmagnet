package dev.chestmagnet;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.item.ItemStack;
/** 只接受服务端搜索事件；不从客户端坐标猜测建筑。 */
public final class CompassTargets {
  private static final String KEY="ChestMagnetCompassV1";
  private static final List<StructureTargetProvider> PROVIDERS=new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final Map<ItemStack,ServerPlayer> OWNERS=Collections.synchronizedMap(new WeakHashMap<>());
  public static void copy(ServerPlayer from,ServerPlayer to){if(from.getPersistentData().contains(KEY))to.getPersistentData().put(KEY,from.getPersistentData().getCompound(KEY).copy());}
  public static void login(ServerPlayer p){if(p.getPersistentData().getCompound(KEY).getString("State").equals("SEARCHING"))state(p,"CANCELLED");}
  public static void register(StructureTargetProvider provider){PROVIDERS.add(Objects.requireNonNull(provider));}
  public static boolean apiPresent(){return CompassMixinSupport.present("com.chaosthedude.explorerscompass.api.StructureSearchSnapshot");}
  public static CompoundTag snapshot(ServerPlayer p){
    for(var provider:PROVIDERS){var t=provider.snapshot(p);if(t!=null)return t.copy();}
    if(apiPresent())try{return ((CompoundTag)Class.forName("com.chaosthedude.explorerscompass.api.StructureSearchSnapshot").getMethod("getSnapshot",ServerPlayer.class).invoke(null,p)).copy();}
    catch(ReflectiveOperationException e){throw new IllegalStateException("指南针快照接口调用失败",e);}
    if(p.getPersistentData().contains(KEY))return p.getPersistentData().getCompound(KEY).copy();
    var t=new CompoundTag();t.putString("State","UNSUPPORTED");return t;
  }
  public static void publish(ServerPlayer p,UUID revision,String state,BlockPos anchor,Object key,ChunkPos start){
    var t=new CompoundTag();t.putInt("Version",1);t.putUUID("Revision",revision);t.putString("State",state);
    t.putString("Dimension",p.level().dimension().location().toString());
    if(state.equals("FOUND")){t.putString("Structure",key.toString());t.putInt("StartX",start.x);t.putInt("StartZ",start.z);t.putLong("Anchor",anchor.asLong());}
    p.getPersistentData().put(KEY,t);
  }
  public static void begin(ServerPlayer p,ItemStack stack){OWNERS.put(stack,p);publish(p,UUID.randomUUID(),"SEARCHING",null,null,null);}
  public static void state(ServerPlayer p,String state){var old=snapshot(p);publish(p,old.hasUUID("Revision")?old.getUUID("Revision"):UUID.randomUUID(),state,null,null,null);}
  public static void failed(ItemStack stack){var p=OWNERS.remove(stack);if(p!=null)state(p,"NOT_FOUND");}
  public static Object field(Object o,String name){
    for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    throw new IllegalStateException("指南针字段不匹配: "+name);
  }
  private static Object call(Object o,String name)throws ReflectiveOperationException{var m=o.getClass().getMethod(name);m.setAccessible(true);return m.invoke(o);}
  public static void lifecycle(Object session,boolean terminal,String result){
    if(apiPresent() || (boolean)field(session,"terminalSent"))return;
    var p=(ServerPlayer)field(session,"player");var token=(UUID)field(session,"token");
    if(!terminal){publish(p,token,"SEARCHING",null,null,null);return;}
    var job=field(session,"job");
    String state=result.equals("found")?(job.getClass().getSimpleName().equals("StructureSearch")?"FOUND":"NON_STRUCTURE"):result.equals("cancelled")?"CANCELLED":"NOT_FOUND";
    try{if(state.equals("FOUND")){var c=call(job,"result");publish(p,token,state,(BlockPos)call(c,"position"),call(c,"key"),(ChunkPos)call(c,"chunk"));}else publish(p,token,state,null,null,null);}
    catch(ReflectiveOperationException e){throw new IllegalStateException("指南针搜索结果接口不匹配",e);}
  }
  public static void found(Object worker,BlockPos anchor,Structure structure,ChunkPos start){
    if(start==null)return;
    if(field(worker,"player") instanceof ServerPlayer p){var old=snapshot(p);publish(p,old.hasUUID("Revision")?old.getUUID("Revision"):UUID.randomUUID(),"FOUND",anchor,p.level().registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure),start);}
  }
}
