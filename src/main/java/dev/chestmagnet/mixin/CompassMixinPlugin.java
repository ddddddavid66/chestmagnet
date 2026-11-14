package dev.chestmagnet.mixin;
import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import dev.chestmagnet.CompassTargets;
import dev.chestmagnet.LootrBridge;
public final class CompassMixinPlugin implements IMixinConfigPlugin {
 public void onLoad(String p){} public String getRefMapperConfig(){return null;}
 private static final String PREFIX = "com.chaosthedude.explorerscompass.";
 private static final String M = "net/minecraft/";
 private static final String C = "com/chaosthedude/explorerscompass/";
 private static final String PLAYER = "L" + M + "world/entity/player/Player;";
 private static final String STACK = "L" + M + "world/item/ItemStack;";
 private static final String LEVEL = "L" + M + "server/level/ServerLevel;";
 private static final String POS = "L" + M + "core/BlockPos;";
 private static final String CHUNK = "L" + M + "world/level/ChunkPos;";
 private static final String KEY = "L" + M + "resources/ResourceLocation;";
 private static final String STRUCTURE = "L" + M + "world/level/levelgen/structure/Structure;";
 private static final String PAIR = "Lcom/mojang/datafixers/util/Pair;";
 private static final String ITEM = C + "items/ExplorersCompassItem";
 private static final String CONTEXT = "L" + C + "worker/SearchContext;";
 private static final String CONFIRM = "(" + CHUNK + STRUCTURE + "I)";
 private static final String ORIGINAL_SUCCESS = "(" + POS + STRUCTURE + ")V";
 private static final String ENHANCE_SUCCESS = "(" + PLAYER + STACK + KEY + "ZIII" + KEY + "Ljava/util/List;IZ)V";
 private enum Adapter { API, UNIFIED, ORIGINAL, ENHANCE_POS, ENHANCE_PAIR, UNSUPPORTED }
 private Adapter adapter;

 public boolean shouldApplyMixin(String target, String mixin) {
   if (mixin.endsWith(".LootrMenuMixin")) return lootrMenuShape(target);
   if (!mixin.contains("Compass")) return true;
   if (adapter == null) adapter = compassAdapter();
   var name = mixin.substring(mixin.lastIndexOf('.') + 1);
   return switch (adapter) {
     case UNIFIED -> name.equals("UnifiedCompassMixin");
     case ORIGINAL -> name.startsWith("OriginalCompass");
     case ENHANCE_POS, ENHANCE_PAIR -> switch (name) {
       case "EnhanceCompassItemMixin", "EnhanceCompassContextMixin", "EnhanceCompassWorkerMixin" -> true;
       case "EnhanceCompassPosMixin", "EnhanceCompassManagerStopMixin" -> adapter == Adapter.ENHANCE_POS;
       case "EnhanceCompassPairMixin", "EnhanceCompassContextStopMixin" -> adapter == Adapter.ENHANCE_PAIR;
       default -> false;
     };
     default -> false;
   };
 }

 private static Adapter compassAdapter() {
   var api = node("api.StructureSearchSnapshot");
   if (method(api, "getSnapshot", "(L" + M + "server/level/ServerPlayer;)L" + M + "nbt/CompoundTag;"))
     return Adapter.API;
   var unified = node("unified.search.SearchService");
   if (unified != null && unified.methods.stream().anyMatch(m -> m.name.equals("lifecycle")
       && m.desc.matches("\\(L[^;]+;ZLjava/lang/String;Ljava/lang/String;\\)V"))) return Adapter.UNIFIED;

   var item = node("items.ExplorersCompassItem");
   var worker = node("worker.StructureSearchWorker");
   if (!method(worker, "getStructureGeneratingAt", "(" + CHUNK + ")" + PAIR)) return Adapter.UNSUPPORTED;
   var context = node("worker.SearchContext");
   var searchWorker = node("worker.SearchWorker");
   if (method(item, "setSearching", "(" + STACK + "L" + C + "util/SearchTarget;" + KEY + PLAYER + ")V")
       && method(item, "fail", "(" + PLAYER + STACK + "II)V")
       && method(item, "cancelSearch", "(L" + M + "world/level/Level;" + PLAYER + STACK + ")V")
       && field(searchWorker, "context", CONTEXT)
       && field(context, "level", LEVEL) && field(context, "player", PLAYER) && field(context, "stack", STACK)
       && method(context, "<init>", "(" + LEVEL + PLAYER + STACK + POS + "Ljava/util/List;ZZ)V")
       && method(context, "reportNotFound", "(II)V")
       && calls(context, "reportLocated", "(" + POS + KEY + "I)V", ITEM, "succeed", ENHANCE_SUCCESS)) {
     if (method(worker, "confirmStructureAt", CONFIRM + PAIR) && method(context, "reportStopped", "()V"))
       return Adapter.ENHANCE_PAIR;
     var manager = node("worker.SearchWorkerManager");
     var grid = node("worker.GridStructureSearchWorker");
     // 旧版仅在传入的结构和区块被确认后返回坐标；验证走的是这一原生确认分支。
     if (method(worker, "confirmStructureAt", CONFIRM + POS) && !method(context, "reportStopped", "()V")
         && field(manager, "context", CONTEXT) && method(manager, "stop", "()V")
         && calls(grid, "confirmNext", "()Z", C + "worker/GridStructureSearchWorker", "confirmStructureAt", CONFIRM + POS))
       return Adapter.ENHANCE_POS;
     return Adapter.UNSUPPORTED;
   }

   if (method(item, "setSearching", "(" + STACK + KEY + PLAYER + ")V")
       && method(item, "setNotFound", "(" + STACK + "II)V")
       && method(item, "setInactive", "(" + STACK + PLAYER + ")V")
       && method(item, "setState", "(" + STACK + POS + "L" + C + "util/CompassState;" + PLAYER + ")V")
       && field(worker, "level", LEVEL) && field(worker, "player", PLAYER) && field(worker, "stack", STACK)
       && (calls(worker, "succeed", ORIGINAL_SUCCESS, ITEM, "succeed", "(" + STACK + KEY + "IIIZ)V")
          ^ calls(worker, "succeed", ORIGINAL_SUCCESS, ITEM, "succeed", "(" + STACK + KEY + "ZIILjava/util/List;IZ)V")))
     return Adapter.ORIGINAL;
   return Adapter.UNSUPPORTED;
 }

 private static ClassNode node(String suffix) {
   try {
     return MixinService.getService().getBytecodeProvider().getClassNode(PREFIX + suffix);
   } catch (ClassNotFoundException | java.io.IOException | RuntimeException | LinkageError ignored) {
     return null;
   }
 }

 private static boolean method(ClassNode n, String name, String desc) {
   return n != null && n.methods.stream().anyMatch(m -> name.equals(m.name) && desc.equals(m.desc));
 }

 private static boolean field(ClassNode n, String name, String desc) {
   return n != null && n.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
 }

 private static boolean calls(ClassNode n, String name, String desc, String owner, String called, String signature) {
   if (!method(n, name, desc)) return false;
   for (var m : n.methods)
     if (name.equals(m.name) && desc.equals(m.desc))
       for (var instruction : m.instructions)
         if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
             && called.equals(call.name) && signature.equals(call.desc)) return true;
   return false;
 }

 private static boolean lootrMenuShape(String target) {
   try {
     var klass = MixinService.getService().getBytecodeProvider().getClassNode(target);
     var found = new HashSet<String>();
     for (var method : klass.methods) {
       if (!Set.of("handleLootChest", "handleLootInventory", "handleLootCart").contains(method.name))
         continue;
       int calls = 0;
       for (var instruction : method.instructions) {
         if (instruction instanceof MethodInsnNode call
             && call.owner.equals("net/minecraft/world/entity/player/Player")
             && call.desc.equals("(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;")
             && (call.name.equals("openMenu") || call.name.equals("m_5893_"))) calls++;
       }
       if (calls != 2 || !found.add(method.name)) return false;
     }
     return found.size() == 3;
   } catch (ClassNotFoundException | java.io.IOException | RuntimeException | LinkageError e) {
     return false;
   }
 }
 public void acceptTargets(Set<String>a,Set<String>b){} public List<String> getMixins(){return null;}
 public void preApply(String t,ClassNode n,String m,IMixinInfo i){} public void postApply(String t,ClassNode n,String m,IMixinInfo i){if(m.endsWith(".LootrMenuMixin")) LootrBridge.menuHookApplied();
   else if (m.endsWith(".OriginalCompassItemMixin") || m.endsWith(".EnhanceCompassItemMixin")
       || m.endsWith(".UnifiedCompassMixin")) CompassTargets.legacyAdapterApplied();}
}
