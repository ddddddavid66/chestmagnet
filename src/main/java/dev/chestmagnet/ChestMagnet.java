package dev.chestmagnet;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.*;

@Mod(ChestMagnet.ID)
public class ChestMagnet {
  public static final String ID = "chestmagnet";
  public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
  public static final DeferredItem<MagnetItem> MAGNET = ITEMS.registerItem("chest_magnet", MagnetItem::new);

  public ChestMagnet(IEventBus bus, ModContainer container) {
    ITEMS.register(bus);
    Net.init(bus);
    bus.addListener(
        (BuildCreativeModeTabContentsEvent e) -> {
          if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(MAGNET);
        });
    NeoForge.EVENT_BUS.register(new Engine());
    container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, Config.SPEC);
  }

  public static class MagnetItem extends Item {
    public MagnetItem(Properties properties) {
      super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
        Level level, Player player, InteractionHand hand) {
      // 实际动作只由可改绑的客户端按下事件发送，原版use不再重复启停。
      return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
      lines.add(Component.literal(Data.enabled(stack) ? "§a已开启" : "§7已关闭"));
      lines.add(Component.literal("§6吸收模式：" + Data.modeName(Data.mode(stack))));
      if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
        ClientEvents.tooltip(lines);
    }
  }
}
