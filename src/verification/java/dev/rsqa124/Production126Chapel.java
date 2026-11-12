package dev.rsqa124;

import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

final class Production126Chapel {
  private static final ResourceLocation CHURCH = new ResourceLocation("goety_revelation", "church");
  private static final ResourceLocation PEDESTAL = new ResourceLocation("goety", "pedestal");
  private static final ResourceLocation ALTAR = new ResourceLocation("goety", "dark_altar");
  private static ItemStack compass, magnet, original;
  private static BlockPos reward, imitation, dark;
  private static String rotation;
  private static int phase, ticks, age;

  static void tick(ServerPlayer player) throws ReflectiveOperationException {
    if (++age > 2400) throw new IllegalStateException("chapel QA timeout, phase " + phase);
    if (phase == 0) {
      prepareSearch(player);
      next();
    } else if (phase == 1) {
      var snapshot = snapshot(player);
      if (snapshot.getString("State").equals("NOT_FOUND") || snapshot.getString("State").equals("CANCELLED"))
        throw new IllegalStateException("Explorer returned " + snapshot);
      if (!snapshot.getString("State").equals("FOUND")) return;
      require(CHURCH.toString().equals(snapshot.getString("Structure")), "native compass chose the church");
      locateFixture(player, snapshot);
      next();
    } else if (phase == 2 && ++ticks >= 30) {
      require(same(stock(player, reward), original), "nearby mode did not take pedestal");
      // 整个物品栏填满，自动任务只能暂停而不能移动基座战利品。
      for (int i = 0; i < 36; i++) if (i != 35) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
      magnet.getOrCreateTagElement("ChestMagnet").putBoolean("AutoStructure", true);
      next();
    } else if (phase == 3 && ++ticks >= 90) {
      require(same(stock(player, reward), original), "full target did not take native loot");
      for (int i = 0; i < 35; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
      next();
    } else if (phase == 4) {
      if (!stock(player, reward).isEmpty()) {
        // 大型新版教堂可能先收满普通箱子。仅在隔离夹具中清出空位，
        // 保留与目标同类、同 NBT 的奖励，避免把真实的满目标暂停当成漏收。
        if (player.getInventory().getFreeSlot() < 0) {
          for (int i = 0; i < 35; i++)
            if (!ItemStack.isSameItemSameTags(player.getInventory().getItem(i), original))
              player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        return;
      }
      require(player.getInventory().items.stream().filter(s -> ItemStack.isSameItemSameTags(s, original))
          .mapToInt(ItemStack::getCount).sum() >= original.getCount(), "native pedestal reward arrives with NBT");
      require(stock(player, imitation).is(Items.DIAMOND), "extra placed pedestal untouched");
      require(stock(player, dark).is(Items.EMERALD), "dark altar untouched");
      require(PEDESTAL.equals(ForgeRegistries.BLOCKS.getKey(player.serverLevel().getBlockState(reward).getBlock())),
          "pedestal preserved");
      NearbyProductionChecks.LOG.info("QA126_CHAPEL_PASS native compass automatic reward={} at {} tagged={} rotation={}",
          original, reward, original.hasTag(), rotation);
      NearbyProductionChecks.done = true;
    }
  }

  private static void prepareSearch(ServerPlayer player) throws ReflectiveOperationException {
    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    player.getInventory().clearContent();
    var nether = player.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
    require(nether != null, "the church generates in the Nether");
    player.setNoGravity(true);
    player.teleportTo(nether, 0.5, 64, 0.5, 0, 0);
    compass = item("explorerscompass:explorerscompass");
    require(!compass.isEmpty(), "native explorer compass registered");
    player.getInventory().setItem(player.getInventory().selected, compass);
    var service = Class.forName("com.chaosthedude.explorerscompass.unified.search.SearchService");
    service.getMethod("requestStructures", net.minecraft.server.level.ServerPlayer.class, ItemStack.class,
        ResourceLocation.class, boolean.class, boolean.class, ResourceLocation.class)
        .invoke(null, player, compass, CHURCH, false, false, null);
  }

  private static CompoundTag snapshot(ServerPlayer player) throws ReflectiveOperationException {
    return (CompoundTag) Class.forName("com.chaosthedude.explorerscompass.api.StructureSearchSnapshot")
        .getMethod("getSnapshot", ServerPlayer.class).invoke(null, player);
  }

  @SuppressWarnings("unchecked")
  private static void locateFixture(ServerPlayer p, CompoundTag snapshot) throws ReflectiveOperationException {
    var level = p.serverLevel();
    var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(CHURCH);
    var start = level.getChunk(snapshot.getInt("StartX"), snapshot.getInt("StartZ")).getStartForStructure(structure);
    require(start != null && start.isValid(), "real generated church start");
    var type = Class.forName("dev.chestmagnet.ChapelPedestals");
    var layout = declared(type, "from", StructureStart.class).invoke(null, start);
    var rewards = (Map<BlockPos, ItemStack>) declared(layout.getClass(), "rewards").invoke(layout);
    var altars = (java.util.List<BlockPos>) declared(layout.getClass(), "altars").invoke(layout);
    require(!rewards.isEmpty() && !altars.isEmpty(), "template layout detects actual pedestal and altar positions");
    var ordered = rewards.entrySet().stream()
        .sorted(java.util.Comparator.comparing((Map.Entry<BlockPos, ItemStack> entry) -> entry.getValue().hasTag())
            .reversed()).toList();
    for (var entry : ordered) {
      var at = entry.getKey();
      level.getChunkAt(at);
      if (same(stock(p, at), entry.getValue())) { reward = at; original = stock(p, at).copy(); break; }
    }
    require(reward != null, "template transformed location matches live native reward");
    rotation = start.getPieces().stream()
        .filter(piece -> piece.getBoundingBox().isInside(reward))
        .filter(net.minecraft.world.level.levelgen.structure.TemplateStructurePiece.class::isInstance)
        .map(net.minecraft.world.level.levelgen.structure.TemplateStructurePiece.class::cast)
        .map(piece -> piece.placeSettings().getRotation().toString()).findFirst().orElse("unknown");
    // 用原生结构元数据检查手动任务不会注册奖励点。
    magnet = item("chestmagnet:chest_magnet");
    var jobs = Class.forName("dev.chestmagnet.StructureJobs");
    var manual = declared(jobs, "create", ServerPlayer.class, ItemStack.class, CompoundTag.class, boolean.class)
        .invoke(null, p, magnet, snapshot, false);
    declared(jobs, "geometry", manual.getClass()).invoke(null, manual);
    var field = manual.getClass().getDeclaredField("chapel");
    field.setAccessible(true);
    require(((Map<?, ?>) declared(layout.getClass(), "rewards").invoke(field.get(manual))).isEmpty(),
        "manual job never registers altar pedestal sources");
    p.getInventory().setItem(p.getInventory().selected, ItemStack.EMPTY);
    p.getInventory().setItem(35, magnet);
    p.setNoGravity(true);
    p.teleportTo(reward.getX() + 0.5, reward.getY() + 2.0, reward.getZ() + 0.5);
    imitation = reward.above(4);
    dark = reward.above(5);
    level.setBlockAndUpdate(imitation, ForgeRegistries.BLOCKS.getValue(PEDESTAL).defaultBlockState());
    level.setBlockAndUpdate(dark, ForgeRegistries.BLOCKS.getValue(ALTAR).defaultBlockState());
    handler(p, imitation).insertItem(0, new ItemStack(Items.DIAMOND), false);
    handler(p, dark).insertItem(0, new ItemStack(Items.EMERALD), false);
    require(stock(p, imitation).is(Items.DIAMOND) && stock(p, dark).is(Items.EMERALD), "negative control fixtures");
    // 半径为零，避免附近模式改变测试数据。
    var tag = magnet.getOrCreateTagElement("ChestMagnet");
    tag.putInt("X", 0); tag.putInt("Y", 0); tag.putInt("Z", 0);
  }

  private static net.minecraftforge.items.IItemHandler handler(ServerPlayer p, BlockPos at) {
    var block = p.serverLevel().getBlockEntity(at);
    return block == null ? null : block.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
  }

  private static ItemStack stock(ServerPlayer p, BlockPos at) {
    var handler = handler(p, at);
    return handler == null || handler.getSlots() == 0 ? ItemStack.EMPTY : handler.getStackInSlot(0);
  }

  private static boolean same(ItemStack a, ItemStack b) {
    return !a.isEmpty() && ItemStack.isSameItemSameTags(a, b) && a.getCount() == b.getCount();
  }

  private static ItemStack item(String id) {
    return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)));
  }

  private static Method declared(Class<?> cls, String name, Class<?>... args) throws ReflectiveOperationException {
    var method = cls.getDeclaredMethod(name, args);
    method.setAccessible(true);
    return method;
  }

  private static void next() { phase++; ticks = 0; }
  private static void require(boolean condition, String message) { Production125Compat.require(condition, message); }
}
