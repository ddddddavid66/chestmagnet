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
 @Group(name="magnetSearchStart", min=1, max=1)
 @Inject(method="setSearching(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/entity/player/Player;)V", at=@At("HEAD"), remap=false, require=0)
 private void magnet$beginLegacy(ItemStack stack, ResourceLocation key, Player player, CallbackInfo ci) {
   if (player instanceof ServerPlayer p) CompassTargets.begin(p, stack);
 }
 @Group(name="magnetSearchStart", min=1, max=1)
 @Inject(method="setSearching(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;ZLnet/minecraft/world/entity/player/Player;)V", at=@At("HEAD"), remap=false, require=0)
 private void magnet$begin(ItemStack stack, ResourceLocation key, boolean exact, Player player, CallbackInfo ci) {
   if (player instanceof ServerPlayer p) CompassTargets.begin(p, stack);
 }
 @Inject(method="setNotFound",at=@At("HEAD"),remap=false)
 private void magnet$fail(ItemStack stack,int radius,int samples,CallbackInfo ci){CompassTargets.failed(stack);}
 @Inject(method="setState",at=@At("HEAD"),remap=false)
 private void magnet$cancel(ItemStack stack,BlockPos at,@Coerce Object state,Player player,CallbackInfo ci){if(player instanceof ServerPlayer p && state instanceof Enum<?> value && value.name().equals("INACTIVE"))CompassTargets.cancel(stack,p);}
 @Inject(method="setInactive",at=@At("HEAD"),remap=false)
 private void magnet$inactive(ItemStack stack,Player player,CallbackInfo ci){if(player instanceof ServerPlayer p)CompassTargets.cancel(stack,p);}
}
