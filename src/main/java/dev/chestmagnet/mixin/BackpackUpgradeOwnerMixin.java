package dev.chestmagnet.mixin;

import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase", remap = false)
public interface BackpackUpgradeOwnerMixin {
  @Accessor("storageWrapper") IStorageWrapper chestmagnet$owner();
}
