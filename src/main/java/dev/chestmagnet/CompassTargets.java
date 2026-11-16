package dev.chestmagnet;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.item.ItemStack;
/** 只接受服务端搜索事件；不从客户端坐标猜测建筑。 */
public final class CompassTargets {
  private static final String KEY="ChestMagnetCompassV1";
  private static final List<StructureTargetProvider> PROVIDERS=new java.util.concurrent.CopyOnWriteArrayList<>();
  private record Ticket(UUID revision, UUID owner, ResourceLocation dimension) {}
  private record Candidate(Structure structure, ChunkPos start) {}
  private static final Map<ItemStack, Ticket> TICKETS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
  private static final Map<ItemStack, java.lang.ref.WeakReference<ServerPlayer>> PLAYERS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
  private static final Map<Object, Ticket> SESSIONS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
  private static final Map<Object, Map<BlockPos, Candidate>> PROOFS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
  private static boolean legacyActive;
  public static void legacyAdapterApplied() { legacyActive = true; }
  public static void copy(ServerPlayer from,ServerPlayer to){if(from.getPersistentData().contains(KEY))to.getPersistentData().put(KEY,from.getPersistentData().getCompound(KEY).copy());}
  public static void login(ServerPlayer p){if(p.getPersistentData().getCompound(KEY).getString("State").equals("SEARCHING"))state(p,"CANCELLED");}
  public static void register(StructureTargetProvider provider){PROVIDERS.add(Objects.requireNonNull(provider));}
  public static CompoundTag snapshot(ServerPlayer p){
    for(var provider:PROVIDERS){var t=provider.snapshot(p);if(t!=null)return t.copy();}
    if (legacyActive) {
      var old = p.getPersistentData().getCompound(KEY);
      if (old.getBoolean("VerifiedBridge128")) return old.copy();
    }
    var t=new CompoundTag();t.putString("State","UNSUPPORTED");return t;
  }
  public static void publish(ServerPlayer p,UUID revision,String state,BlockPos anchor,Object key,ChunkPos start){
    publish(p, revision, state, anchor, key, start, p.serverLevel().dimension().location());
  }
  private static void publish(ServerPlayer p,UUID revision,String state,BlockPos anchor,Object key,ChunkPos start,ResourceLocation dimension){
    var t=new CompoundTag();t.putInt("Version",1);t.putUUID("Revision",revision);t.putString("State",state);
    t.putString("Dimension",dimension.toString());
    if(state.equals("FOUND")){t.putString("Structure",key.toString());t.putInt("StartX",start.x);t.putInt("StartZ",start.z);t.putLong("Anchor",anchor.asLong());}
    t.putBoolean("VerifiedBridge128", true);
    p.getPersistentData().put(KEY,t);
  }
  public static void begin(ServerPlayer p, ItemStack stack) {
    var ticket = new Ticket(UUID.randomUUID(), p.getUUID(), p.serverLevel().dimension().location());
    TICKETS.put(stack, ticket);
    PLAYERS.put(stack, new java.lang.ref.WeakReference<>(p));
    publish(p, ticket.revision(), "SEARCHING", null, null, null);
  }
  public static void state(ServerPlayer p,String state){var old=snapshot(p);publish(p,old.hasUUID("Revision")?old.getUUID("Revision"):UUID.randomUUID(),state,null,null,null);}
  private static boolean latest(ServerPlayer p, Ticket ticket) {
    if (ticket == null || !ticket.owner().equals(p.getUUID())
        || p.getServer().getPlayerList().getPlayer(ticket.owner()) != p) return false;
    var last = p.getPersistentData().getCompound(KEY);
    return last.hasUUID("Revision") && last.getUUID("Revision").equals(ticket.revision());
  }

  private static boolean current(ServerPlayer p, Ticket ticket) {
    return latest(p, ticket) && p.getPersistentData().getCompound(KEY).getString("State").equals("SEARCHING");
  }

  public static void cancel(ItemStack stack, ServerPlayer p) {
    var ticket = TICKETS.get(stack);
    if (latest(p, ticket)) publish(p, ticket.revision(), "CANCELLED", null, null, null, ticket.dimension());
    // 重登后的已找到结果没有内存票据，玩家主动取消仍应清除它。
    else if (ticket == null && !p.getPersistentData().getCompound(KEY).getString("State").equals("SEARCHING"))
      state(p, "CANCELLED");
  }

  public static void failed(ItemStack stack) {
    var owner = PLAYERS.get(stack);
    var player = owner == null ? null : owner.get();
    if (player != null) failed(stack, player);
  }

  public static void failed(ItemStack stack, ServerPlayer p) {
    var ticket = TICKETS.get(stack);
    if (current(p, ticket)) publish(p, ticket.revision(), "NOT_FOUND", null, null, null, ticket.dimension());
  }

  public static void attach(Object session, ServerLevel level, ServerPlayer p, ItemStack stack) {
    var ticket = TICKETS.get(stack);
    if (current(p, ticket) && ticket.dimension().equals(level.dimension().location()))
      SESSIONS.put(session, ticket);
  }

  public static void candidate(Object session, ChunkPos chunk, Pair<BlockPos, Structure> result) {
    if (result != null) candidate(session, chunk, result.getFirst(), result.getSecond());
  }

  public static void candidate(Object session, ChunkPos chunk, BlockPos anchor, Structure structure) {
    if (session == null || anchor == null || structure == null || SESSIONS.get(session) == null) return;
    // 异步候选可能落败；只关联真正返回的坐标对象，不把最后访问的区块当作获胜者。
    PROOFS.computeIfAbsent(session, unused -> new com.google.common.collect.MapMaker().weakKeys().makeMap())
        .put(anchor, new Candidate(structure, chunk));
  }

  public static void structureResult(Object session, ServerLevel level, ServerPlayer p, BlockPos pos,
                                     Structure expected, ResourceLocation key, boolean allowBiome) {
    if (!level.getServer().isSameThread()) {
      level.getServer().execute(() -> structureResult(session, level, p, pos, expected, key, allowBiome));
      return;
    }
    var ticket = SESSIONS.get(session);
    if (!current(p, ticket)) return;
    var candidates = PROOFS.get(session);
    var proof = candidates == null ? null : candidates.get(pos);
    if (proof != null) {
      var actual = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(proof.structure());
      if (actual != null && (expected == null || expected == proof.structure()) && (key == null || key.equals(actual))) {
        var anchor = level.isOutsideBuildHeight(pos) ? new BlockPos(pos.getX(), level.getSeaLevel(), pos.getZ()) : pos;
        publish(p, ticket.revision(), "FOUND", anchor, actual, proof.start(), ticket.dimension());
        return;
      }
    }
    publish(p, ticket.revision(), allowBiome && proof == null ? "NON_STRUCTURE" : "NOT_FOUND",
        null, null, null, ticket.dimension());
  }

  public static void stopped(Object session, ServerPlayer p, String outcome) {
    var ticket = SESSIONS.get(session);
    if (current(p, ticket)) publish(p, ticket.revision(), outcome, null, null, null, ticket.dimension());
  }

  public static Object field(Object o,String name){
    for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    throw new IllegalStateException("指南针字段不匹配: "+name);
  }
}
