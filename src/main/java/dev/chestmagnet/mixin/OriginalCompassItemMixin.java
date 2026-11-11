package dev.chestmagnet.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import dev.chestmagnet.CompassTargets;
@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.items.ExplorersCompassItem",remap=false)
public abstract class OriginalCompassItemMixin {
 @Inject(method="setSearching",at=@At("HEAD"),remap=false)
 private void magnet$begin(ItemStack stack,ResourceLocation key,Player player,CallbackInfo ci){if(player instanceof ServerPlayer p)CompassTargets.begin(p,stack);}
 @Inject(method="setNotFound",at=@At("HEAD"),remap=false)
 private void magnet$fail(ItemStack stack,int radius,int samples,CallbackInfo ci){CompassTargets.failed(stack);}
 @Inject(method="setState",at=@At("HEAD"),remap=false)
 private void magnet$cancel(ItemStack stack,BlockPos at,@Coerce Object state,Player player,CallbackInfo ci){if(player instanceof ServerPlayer p && state.toString().equals("INACTIVE"))CompassTargets.state(p,"CANCELLED");}
 @Inject(method="setInactive",at=@At("HEAD"),remap=false)
 private void magnet$inactive(ItemStack stack,Player player,CallbackInfo ci){if(player instanceof ServerPlayer p)CompassTargets.state(p,"CANCELLED");}
}
