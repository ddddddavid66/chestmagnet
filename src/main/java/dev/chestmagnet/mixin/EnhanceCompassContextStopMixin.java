package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.SearchContext", remap=false)
public abstract class EnhanceCompassContextStopMixin {
  @Inject(method="reportStopped()V", at=@At("TAIL"), remap=false)
  private void magnet$stopped(CallbackInfo ci) {
    if (CompassTargets.field(this, "player") instanceof ServerPlayer player)
      CompassTargets.stopped(this, player, "CANCELLED");
  }
}
