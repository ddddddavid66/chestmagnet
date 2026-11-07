package dev.chestmagnet;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(ChestMagnet.ID)
public class ChestMagnet {
  public static final String ID = "chestmagnet";
  public static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(ForgeRegistries.ITEMS, ID);
  public static final RegistryObject<Item> MAGNET = ITEMS.register("chest_magnet", MagnetItem::new);

  public ChestMagnet() {
    var bus = FMLJavaModLoadingContext.get().getModEventBus();
    ITEMS.register(bus);
    Net.init();
    bus.addListener(
        (BuildCreativeModeTabContentsEvent e) -> {
          if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(MAGNET);
        });
    MinecraftForge.EVENT_BUS.register(new Engine());
    net.minecraftforge.fml.ModLoadingContext.get()
        .registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, Config.SPEC);
  }

  public static class MagnetItem extends Item {
    public MagnetItem() {
      super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
        Level level, Player player, InteractionHand hand) {
      if (!level.isClientSide && !player.isShiftKeyDown())
        Engine.toggle((net.minecraft.server.level.ServerPlayer) player, player.getItemInHand(hand));
      return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(
        ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.literal(Data.enabled(stack) ? "§a已开启" : "§7已关闭"));
      lines.add(Component.literal("§6吸收模式：" + Data.modeName(Data.mode(stack))));
      lines.add(Component.literal("§7右键：启停 / 潜行左键空气：设置"));
      lines.add(Component.literal("§7潜行右键容器或储物矿车：排除"));
    }
  }
}
