package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import com.refinedmods.refinedstorage.apiimpl.network.node.WirelessTransmitterNetworkNode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public class RsWirelessTests {
  /** 使用真实节点/升级槽/卡片及已应用的扩展 mixin，只固定网络激活状态以隔离覆盖判定。 */
  private static final class Transmitter extends WirelessTransmitterNetworkNode {
    boolean active = true;
    ResourceKey<Level> dimension;

    Transmitter(Level level, BlockPos pos) {
      super(level, pos);
      dimension = level.dimension();
    }

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public ResourceKey<Level> getDimension() {
      return dimension;
    }
  }

  @GameTest(template = "empty")
  public static void wirelessCoverage(GameTestHelper h) {
    var player =
        FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "RsRange122"));
    var origin = h.absolutePos(new BlockPos(2, 1, 2));
    var transmitter = new Transmitter(h.getLevel(), origin);
    player.setPos(origin.getX(), origin.getY(), origin.getZ());
    h.assertTrue(RsCompat.covers(player, transmitter), "native local coverage");
    player.setPos(origin.getX() + 100000, origin.getY(), origin.getZ());
    h.assertTrue(!RsCompat.covers(player, transmitter), "no card cannot reach distant player");
    transmitter.dimension =
        h.getLevel().dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
    h.assertTrue(!RsCompat.covers(player, transmitter), "no card cannot cross dimensions");
    if (!ModList.get().isLoaded("rsinfinitybooster")) {
      org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
          .info("RS native coverage passed; Booster card checks NOT RUN (mod absent)");
      h.succeed();
      return;
    }
    var infinity =
        ForgeRegistries.ITEMS.getValue(new ResourceLocation("rsinfinitybooster", "infinity_card"));
    var dimension =
        ForgeRegistries.ITEMS.getValue(new ResourceLocation("rsinfinitybooster", "dimension_card"));
    h.assertTrue(infinity != null && dimension != null, "real Booster cards registered");
    transmitter.getUpgrades().setStackInSlot(0, new ItemStack(infinity));
    h.assertTrue(
        !RsCompat.covers(player, transmitter), "infinity card alone cannot cross dimensions");
    transmitter.dimension = h.getLevel().dimension();
    h.assertTrue(RsCompat.covers(player, transmitter), "infinity getRange mixin applied");
    transmitter.getUpgrades().setStackInSlot(0, new ItemStack(dimension));
    transmitter.dimension =
        h.getLevel().dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
    var previousMenu = player.containerMenu;
    h.assertTrue(
        RsCompat.covers(player, transmitter), "dimension card permits cross-dimensional coverage");
    h.assertTrue(player.containerMenu == previousMenu, "range query never opens grid GUI");
    transmitter.active = false;
    h.assertTrue(
        !RsCompat.covers(player, transmitter), "inactive dimensional transmitter rejected");
    org.slf4j.LoggerFactory.getLogger("chestmagnet.tests")
        .info("RS native + installed Booster real-card coverage checks passed");
    h.succeed();
  }
}
