package dev.chestmagnet.mixin;

import dev.chestmagnet.LootCapture;
import dev.chestmagnet.SourceExtras;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "se.mickelus.tetra.blocks.salvage.BlockInteraction", remap = false)
public class TetraDropMixin {
  @Inject(
      method =
          "dropLoot(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/state/BlockState;)V",
      at = @At("HEAD"),
      cancellable = true,
      require = 1,
      remap = false)
  private static void capture(
      ResourceLocation table,
      Player p,
      InteractionHand h,
      ServerLevel l,
      BlockState s,
      CallbackInfo ci) {
    // 原方法会直接加人物栏；仅限当前玩家的已核对锁碎片表，其余手动操作保持原样。
    if (!(p instanceof net.minecraft.server.level.ServerPlayer sp)
        || l != sp.serverLevel()
        || !table.equals(new ResourceLocation("tetra:forged/lock_break"))
        || !LootCapture.accepts(p, table)) return;
    SourceExtras.tetraLoot(table, sp, LootCapture.tool(), LootCapture.sourcePos(), s)
        .forEach(LootCapture::add);
    ci.cancel();
  }
}
