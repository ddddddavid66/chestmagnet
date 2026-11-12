package dev.chestmagnet.mixin;
import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import dev.chestmagnet.CompassMixinSupport;
import dev.chestmagnet.LootrBridge;
public final class CompassMixinPlugin implements IMixinConfigPlugin {
 public void onLoad(String p){} public String getRefMapperConfig(){return null;}
 public boolean shouldApplyMixin(String target,String mixin){if(mixin.endsWith(".LootrMenuMixin")) return lootrMenuShape(target); if(!mixin.contains("Compass"))return true; if(CompassMixinSupport.present("com.chaosthedude.explorerscompass.api.StructureSearchSnapshot"))return false; return CompassMixinSupport.present(target) && (!mixin.contains("OriginalCompass") || CompassMixinSupport.present("com.chaosthedude.explorerscompass.worker.StructureSearchWorker"));}

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
 public void preApply(String t,ClassNode n,String m,IMixinInfo i){} public void postApply(String t,ClassNode n,String m,IMixinInfo i){if(m.endsWith(".LootrMenuMixin")) LootrBridge.menuHookApplied();}
}
