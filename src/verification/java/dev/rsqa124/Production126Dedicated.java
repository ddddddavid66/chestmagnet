package dev.rsqa124;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 正式 JAR 无可选模组烟测：真实服务端，独立辅助模组。 */
@Mod.EventBusSubscriber(modid = "rsqa124")
public final class Production126Dedicated {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestmagnet-qa126");
  private static boolean checked;

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent e) {
    if (checked || e.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.qa126Server")) return;
    checked = true;
    try {
      var server = e.getServer();
      var level = server.overworld();
      var at = new BlockPos(7, 110, 7);
      var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CM126Minimal"));
      player.setPos(at.getX() + 0.5, at.getY(), at.getZ() - 0.5);
      var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet", "chest_magnet"));
      var magnet = new ItemStack(item);
      player.getInventory().setItem(0, magnet);
      level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
      var barrel = (BarrelBlockEntity) level.getBlockEntity(at);
      barrel.setItem(0, new ItemStack(Items.DIAMOND, 11));
      var engineType = Class.forName("dev.chestmagnet.Engine");
      var stateType = Class.forName("dev.chestmagnet.Engine$State");
      var ctor = stateType.getDeclaredConstructor();
      ctor.setAccessible(true);
      var work = engineType.getDeclaredMethod("work", ServerPlayer.class, ItemStack.class, stateType, long.class);
      work.setAccessible(true);
      var engine = engineType.getDeclaredConstructor().newInstance();
      var state = ctor.newInstance();
      magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 3);
      work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
      require(barrel.getItem(0).getCount() == 11, "optional mode never extracts without its mod");
      magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 0);
      for (int i = 0; i < 4 && !barrel.isEmpty(); i++) work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
      require(barrel.isEmpty() && player.getInventory().countItem(Items.DIAMOND) == 11,
          "minimal nearby collection uses normal inventory");
      if (net.minecraftforge.fml.ModList.get().isLoaded("beyonddimensions")) {
        var cls = Class.forName("com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet");
        var network = cls.getMethod("createNewNetForPlayer", net.minecraft.world.entity.player.Player.class,
            long.class, int.class).invoke(null, player, 5L, 1);
        require(network != null, "native network");
        barrel.setItem(0, new ItemStack(Items.EMERALD, 8));
        magnet.getOrCreateTagElement("ChestMagnet").putInt("Mode", 3);
        for (int i = 0; i < 3; i++) work.invoke(engine, player, magnet, state, Long.MAX_VALUE);
        var view = dev.chestmagnet.Destinations.get(player, 3);
        require(barrel.getItem(0).getCount() == 3
            && view.count(new ItemStack(Items.EMERALD), itemStack -> itemStack.is(Items.EMERALD),
                99, Long.MAX_VALUE) == 5, "native partial insertion retains three in source");
        LOG.info("QA126_FLOOR_STORAGE_PASS 5 stored, 3 untouched");
      } else {
        LOG.info("QA126_MINIMAL_PASS official server: unavailable optional mode preserved source, normal mode moved 11");
      }
    } catch (Throwable error) {
      LOG.error("QA126_MINIMAL_FAILED", error);
    } finally {
      e.getServer().halt(false);
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private Production126Dedicated() {}
}
