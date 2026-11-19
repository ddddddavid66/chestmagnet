package dev.rsqa124.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets={"dev.chestmagnet.PlayerQuestStock","dev.chestmagnet.BackpackQuestStock","dev.chestmagnet.QuestStock$Indexed"},remap=false)
public abstract class QuestReadCountMixin {
 @Inject(method="<init>",at=@At("RETURN"),require=1)
 private void readerCreated(CallbackInfo ci){if(dev.rsqa124.QuestTiming.countReaders)dev.rsqa124.QuestTiming.readers++;}
}
