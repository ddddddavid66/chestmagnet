package dev.rsqa124.mixin;

import dev.rsqa124.BenchCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.chestmagnet.Sources", remap = false)
public abstract class SourceCountMixin {
  @Inject(method = "eligible", at = @At("HEAD"), remap = false)
  private static void countCandidate(Object value, CallbackInfoReturnable<Boolean> result) {
    BenchCounters.candidate();
  }
}
