package dev.chestmagnet.mixin;

import dev.chestmagnet.CompassTargets;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.SearchWorkerManager", remap=false)
public abstract class EnhanceCompassManagerStopMixin {
  @Inject(method="stop()V", at=@At("TAIL"), remap=false)
  private void magnet$stopped(CallbackInfo ci) {
    var context = CompassTargets.field(this, "context");
    // 开始下一次搜索时也会停掉旧管理器，必须拿旧上下文校验版本。
    if (context != null && CompassTargets.field(context, "player") instanceof ServerPlayer player)
      CompassTargets.stopped(context, player, "CANCELLED");
  }
}
