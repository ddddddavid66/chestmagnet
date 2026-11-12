package dev.chestmagnet;

import com.mojang.authlib.GameProfile;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("chestmagnet")
@PrefixGameTestTemplate(false)
public final class Storage126Tests {
  private static ServerPlayer player(net.minecraft.server.level.ServerLevel level, String name) {
    return FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), name));
  }

  @GameTest(template = "empty")
  public static void beyondPrimaryAndRemainder(GameTestHelper h) {
    var p = player(h.getLevel(), "BD126Owner");
    var other = player(h.getLevel(), "BD126Other");
    var diamond = new ItemStack(Items.DIAMOND, 7);
    var key = new ItemStackKey(diamond);
    h.assertTrue(Destinations.get(p, 3).unavailable(), "does not create a default network");
    var first = DimensionsNet.createNewNetForPlayer(p, 5L, 1);
    var second = DimensionsNet.createNewNetForPlayer(other, 100L, 3);
    h.assertTrue(first != null && second != null && first != second, "two native networks");
    h.assertTrue(!DimensionsNet.setPrimaryNetForPlayer(p, second), "native rejects nonmember");
    var dest = Destinations.get(p, 3);
    h.assertTrue(!dest.unavailable(), dest.reason());
    h.assertTrue(dest.insert(diamond, true).getCount() == 2, "simulation respects native capacity");
    h.assertTrue(first.getUnifiedStorage().getStackByKey(key).amount() == 0, "simulation is read only");
    h.assertTrue(dest.insert(diamond, false).getCount() == 2, "partial insert keeps remainder");
    h.assertTrue(dest.count(diamond, s -> s.is(Items.DIAMOND), 99, Long.MAX_VALUE) == 5, "indexed stock");
    h.assertTrue(first.getUnifiedStorage().getStackByKey(key).amount() == 5
        && second.getUnifiedStorage().getStackByKey(key).amount() == 0, "no fallthrough between networks");
    // GameTestServer 未必安装 Forge 的当前服务器引用，授权换网由正式客户端覆盖。
    var proxyBlock = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
        new net.minecraft.resources.ResourceLocation("beyonddimensions", "net_interface"));
    h.assertTrue(proxyBlock != null, "native network proxy registered");
    h.setBlock(2, 1, 2, proxyBlock);
    var proxy = h.getBlockEntity(new BlockPos(2, 1, 2));
    h.assertTrue(proxy != null && !Sources.eligible(proxy), "network proxy is never scanned as a source");
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void beyondNetherBarrel(GameTestHelper h) {
    var home = h.getLevel();
    var binder = player(home, "BD126Cross");
    var net = DimensionsNet.createNewNetForPlayer(binder, 100L, 4);
    var remote = home.getServer().getLevel(Level.NETHER);
    h.assertTrue(remote != null, "Nether exists");
    var p = FakePlayerFactory.get(remote, binder.getGameProfile());
    var at = new BlockPos(126, 75, 126);
    p.setPos(at.getX() + 0.5, at.getY(), at.getZ() - 0.5);
    remote.getChunkAt(at);
    remote.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
    var barrel = (BarrelBlockEntity) remote.getBlockEntity(at);
    barrel.setItem(0, new ItemStack(Items.EMERALD, 11));
    var magnet = new ItemStack(ChestMagnet.MAGNET.get());
    Data.tag(magnet).putInt("Mode", 3);
    p.getInventory().setItem(15, magnet);
    var engine = new Engine();
    var state = new Engine.State();
    for (int tick = 0; tick < 4 && !barrel.isEmpty(); tick++)
      engine.work(p, magnet, state, Long.MAX_VALUE);
    h.assertTrue(barrel.isEmpty() && net.getUnifiedStorage()
        .getStackByKey(new ItemStackKey(new ItemStack(Items.EMERALD))).amount() == 11, "Nether barrel to primary network");
    h.assertTrue(p.inventoryMenu == p.containerMenu, "no GUI");
    remote.removeBlock(at, false);
    h.succeed();
  }
}
