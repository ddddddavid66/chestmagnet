package dev.rsqa124.mixin;

import dev.rsqa124.QuestTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.chestmagnet.QuestCompat", remap = false)
public abstract class QuestTimingMixin {
  @Inject(method = "step", at = @At("HEAD"), remap = false)
  private static void qa126Start(CallbackInfoReturnable<Boolean> cir) {
    QuestTiming.begin();
  }

  @Inject(method = "step", at = @At("RETURN"), remap = false)
  private static void qa126End(CallbackInfoReturnable<Boolean> cir) {
    QuestTiming.end();
  }
}
