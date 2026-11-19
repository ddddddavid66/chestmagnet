package dev.rsqa124.mixin;

import dev.rsqa124.Production128Ui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Screen.class, remap = false)
public abstract class RepairShiftMixin {
  @Inject(method = {"hasShiftDown", "m_96638_"}, at = @At("HEAD"), cancellable = true, require = 1)
  private static void testShift(CallbackInfoReturnable<Boolean> cir) {
    var shift = Production128Ui.tooltipShift;
    if (shift != null) cir.setReturnValue(shift);
  }
}
