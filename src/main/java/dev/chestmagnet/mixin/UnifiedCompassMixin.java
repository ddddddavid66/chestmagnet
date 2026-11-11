package dev.chestmagnet.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.chestmagnet.CompassTargets;
@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.unified.search.SearchService",remap=false)
public abstract class UnifiedCompassMixin {
 @Inject(method="lifecycle",at=@At("HEAD"),remap=false)
 private static void magnet$lifecycle(@Coerce Object session,boolean terminal,String result,String found,CallbackInfo ci){CompassTargets.lifecycle(session,terminal,result);}
}
