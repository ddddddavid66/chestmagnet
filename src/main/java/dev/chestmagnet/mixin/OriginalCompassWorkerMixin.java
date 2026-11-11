package dev.chestmagnet.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import com.mojang.datafixers.util.Pair;
import dev.chestmagnet.CompassTargets;
@Pseudo
@Mixin(targets="com.chaosthedude.explorerscompass.worker.StructureSearchWorker",remap=false)
public abstract class OriginalCompassWorkerMixin {
 @Unique private ChunkPos magnet$start;
 @Inject(method="getStructureGeneratingAt",at=@At("RETURN"),remap=false)
 private void magnet$candidate(ChunkPos chunk,CallbackInfoReturnable<Pair<BlockPos,Structure>> ci){if(ci.getReturnValue()!=null)magnet$start=chunk;}
 @Inject(method="succeed",at=@At("HEAD"),remap=false)
 private void magnet$found(BlockPos at,Structure structure,CallbackInfo ci){CompassTargets.found(this,at,structure,magnet$start);}
}
