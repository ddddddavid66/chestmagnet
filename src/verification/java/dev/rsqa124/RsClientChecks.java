package dev.rsqa124;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 隔离世界中的真实客户端检查：原生 use 打开终端，不伪造 GridScreen。 */
@Mod.EventBusSubscriber(modid = "rsqa124", value = Dist.CLIENT)
public final class RsClientChecks {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("rs-client-qa");
  private static volatile INetwork network;
  private static volatile boolean ready;
  private static int ticks, phaseTicks, opened, failures;
  private static volatile DiskDriveBlockEntity drive;
  private static volatile int remountDelay;
  private static ItemStack removedDisk = ItemStack.EMPTY;
  private static boolean started, opening, worldRequested;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("chestmagnet.rsClient")) return;
    var mc = Minecraft.getInstance();
    if (++ticks > 12000) { LOG.error("RS_CLIENT_INCOMPLETE timeout"); mc.stop(); return; }
    if (mc.player == null || mc.getSingleplayerServer() == null) {
      if (mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen
          && mc.screen.getTitle().getString().equals(net.minecraft.network.chat.Component
              .translatable("selectWorld.experimental.title").getString())) {
        // 仅确认隔离测试世界的实验设置提示，不处理其他确认框。
        mc.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
            .map(child -> (net.minecraft.client.gui.components.Button) child).findFirst()
            .ifPresent(net.minecraft.client.gui.components.Button::onPress);
      }
      if (ticks % 200 == 0) {
        LOG.info("RS_QA_WAIT screen={}", mc.screen == null ? "none" : mc.screen.getClass().getName());
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), text -> LOG.info("RS_WAIT_SCREENSHOT {}", text.getString()));
      }
      if (!worldRequested && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
        worldRequested = true;
        mc.createWorldOpenFlows().loadLevel(mc.screen, "RsQa124");
      }
      return;
    }
    var server = mc.getSingleplayerServer();
    var playerId = mc.player.getUUID();
    if (!started) {
      started = true;
      server.execute(() -> setup(mc));
    }
    server.execute(() -> {
      if (network == null) return;
      if (!removedDisk.isEmpty() && --remountDelay <= 0) {
        ((net.minecraftforge.items.IItemHandlerModifiable) drive.getNode().getDisks()).setStackInSlot(0, removedDisk);
        removedDisk = ItemStack.EMPTY;
        LOG.info("RS_REMOUNT restored disk");
      }
      network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
      var player = server.getPlayerList().getPlayer(playerId);
      if (player != null && System.getProperty("chestmagnet.qaMode", "off").equals("active")) {
        var barrel = player.serverLevel().getBlockEntity(new BlockPos(0, 120, 1));
        if (barrel instanceof net.minecraft.world.Container container)
          container.setItem(0, new ItemStack(Items.EMERALD, 1));
        player.getMainHandItem().getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
            .ifPresent(energy -> energy.receiveEnergy(Integer.MAX_VALUE, false));
      }
      if (!ready && network.canRun() && !network.getItemStorageCache().getStorages().isEmpty()) {
        var left = network.insertItem(new ItemStack(Items.DIAMOND, 32), 32, Action.PERFORM);
        ready = left.isEmpty();
        if (ready) LOG.info("RS_SERVER_READY entries={} diamonds=32", network.getItemStorageCache().getList().getStacks().size());
      }
    });
    if (!ready) return;
    if (!opening) {
      if (++phaseTicks < 20) return;
      mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
      opening = true; phaseTicks = 0;
      return;
    }
    phaseTicks++;
    if (phaseTicks == 40 && mc.screen instanceof GridScreen screen) screen.getSearchField().setValue("");
    if (phaseTicks < 100) return;
    int all = mc.screen instanceof GridScreen screen ? screen.getView().getAllStacks().size() : -1;
    int visible = mc.screen instanceof GridScreen screen ? screen.getView().getStacks().size() : -1;
    server.execute(() -> LOG.info("RS_SERVER_OBSERVATION entries={} total={}",
        network.getItemStorageCache().getList().getStacks().size(),
        network.getItemStorageCache().getList().getStacks().stream().mapToLong(entry -> entry.getStack().getCount()).sum()));
    LOG.info("RS_CLIENT_OBSERVATION open={} menu={} all={} visible={} screen={}",
        ++opened, mc.player.containerMenu.containerId, all, visible,
        mc.screen == null ? "none" : mc.screen.getClass().getName());
    if (all <= 0 || visible <= 0) failures++;
    // 先保存故障界面，再关闭菜单；截图属于真实客户端帧。
    if (opened == 1 || all <= 0 || visible <= 0)
      Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), text -> LOG.info("RS_SCREENSHOT {}", text.getString()));
    if (opened % 3 == 0 && opened <= 15 && drive != null) {
      server.execute(() -> {
        var slots = (net.minecraftforge.items.IItemHandlerModifiable) drive.getNode().getDisks();
        removedDisk = slots.getStackInSlot(0);
        remountDelay = 10;
        slots.setStackInSlot(0, ItemStack.EMPTY);
        LOG.info("RS_REMOUNT removed disk after observation {}", opened);
      });
    }
    if (opened >= 20) {
      LOG.info("RS_CLIENT_FINISHED opens={} failures={} (limited list smoke; no packet/remount acceptance)", opened, failures);
      mc.stop(); return;
    }
    mc.player.closeContainer(); opening = false; phaseTicks = 0;
  }

  private static void setup(Minecraft mc) {
    try {
      var server = mc.getSingleplayerServer();
    var playerId = mc.player.getUUID();
      var p = server.getPlayerList().getPlayer(mc.player.getUUID());
      var level = p.serverLevel();
      var at = new BlockPos(0, 120, 0);
      for (int x = -3; x <= 4; x++) for (int z = -3; z <= 4; z++)
        level.setBlockAndUpdate(new BlockPos(x, 119, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
      p.teleportTo(0.5, 120, 2.5);
      for (int x = -1; x < 5; x++) for (int y = 0; y < 3; y++)
        level.setBlockAndUpdate(at.offset(x, y, 0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
      var controller = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:controller"));
      level.setBlockAndUpdate(at, controller.defaultBlockState());
      network = ((ControllerBlockEntity) level.getBlockEntity(at)).getNetwork();
      var storage = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:1k_storage_block"));
      String diskId = System.getProperty("chestmagnet.qaDisk", "");
      if (diskId.isEmpty()) level.setBlockAndUpdate(at.east(), storage.defaultBlockState());
      else {
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:disk_drive"));
        level.setBlockAndUpdate(at.east(), block.defaultBlockState());
        drive = (DiskDriveBlockEntity) level.getBlockEntity(at.east());
        var disk = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(diskId)));
        disk.getItem().inventoryTick(disk, level, p, 0, false);
        ((net.minecraftforge.items.IItemHandlerModifiable) drive.getNode().getDisks()).setStackInSlot(0, disk);
      }
      var transmitter = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("refinedstorage:wireless_transmitter"));
      level.setBlockAndUpdate(at.above(), transmitter.defaultBlockState().setValue(
          ((com.refinedmods.refinedstorage.block.BaseBlock) transmitter).getDirection().getProperty(), Direction.DOWN));
      network.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
      network.getNodeGraph().invalidate(Action.PERFORM, level, at);
      var terminal = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("refinedstorageaddons:wireless_crafting_grid")));
      String mode = System.getProperty("chestmagnet.qaMode", "off");
      var magnetItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet:chest_magnet"));
      if (magnetItem != null && !magnetItem.equals(Items.AIR)) {
        var magnet = new ItemStack(magnetItem);
        var tag = magnet.getOrCreateTagElement("ChestMagnet");
        tag.putInt("Mode", 1); tag.putBoolean("Enabled", !mode.equals("off"));
        p.getInventory().setItem(8, magnet);
      }
      if (mode.equals("active")) {
        level.setBlockAndUpdate(at.south(), net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
      }
      p.getInventory().selected = 0;
      p.setItemInHand(InteractionHand.MAIN_HAND, terminal);
      terminal.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
      terminal.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
          .ifPresent(energy -> energy.receiveEnergy(Integer.MAX_VALUE, false));
      LOG.info("RS_CLIENT_FIXTURE disk={} mode={}", diskId, mode);
    } catch (Exception ex) { LOG.error("RS_CLIENT_SETUP_FAILED", ex); }
  }
}
