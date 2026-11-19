package dev.rsqa124;

import dev.chestmagnet.Data;
import dev.chestmagnet.Sources;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 范围/结构共用的规则索引原型；只测规则检查，不当作端到端收取测速。 */
@Mod.EventBusSubscriber(modid = "rsqa124")
public final class SourcePolicyScreen {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("magnet-policy-qa");
  private static final boolean ENABLED = Boolean.getBoolean("chestmagnet.policyScreen");
  private static final int PROBES = 64;
  private static int ticks;

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent event) {
    if (!ENABLED || event.phase != TickEvent.Phase.END || ++ticks != 20) return;
    try {
      var level = event.getServer().overworld();
      var p = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "PolicyScreen"));
      var magnet = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("chestmagnet", "chest_magnet")));
      var chunk = level.getChunkAt(level.getSharedSpawnPos());
      var refs = new Sources.Ref[PROBES];
      for (int i = 0; i < PROBES; i++) {
        var pos = new BlockPos(chunk.getPos().getMinBlockX() + i % 8, 120, chunk.getPos().getMinBlockZ() + i / 8);
        level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        refs[i] = new Sources.Ref(pos, null);
      }
      Class<?> faults = Class.forName("dev.chestmagnet.Faults");
      Method skipped = faults.getDeclaredMethod("skipped", ServerPlayer.class, ItemStack.class, Sources.Ref.class);
      skipped.setAccessible(true);
      MethodHandle method = MethodHandles.lookup().unreflect(skipped);
      for (int count : new int[]{0, 96}) {
        prepare(p, magnet, refs, count);
        run(p, magnet, refs, method, count);
      }
      LOG.info("QA128_POLICY_PASS");
    } catch (Throwable error) {
      LOG.error("QA128_FAILURE policy-screen", error);
    }
    event.getServer().halt(false);
  }

  private static void prepare(ServerPlayer p, ItemStack magnet, Sources.Ref[] refs, int count) {
    var root = Data.tag(magnet);
    var excludes = new ListTag();
    var faults = new ListTag();
    for (int i = 0; i < count; i++) {
      var ex = new CompoundTag();
      ex.putString("Key", i % 12 == 0 ? refs[i % PROBES].key(p) : "filler-excluded-" + i);
      excludes.add(ex);
      var skip = new CompoundTag();
      skip.putString("Key", i % 13 == 0 ? refs[(i + 3) % PROBES].key(p) : "filler-skip-" + i);
      skip.putString("Dimension", p.level().dimension().location().toString());
      skip.putLongArray("Members", new long[]{i % 17 == 0 ? refs[(i + 4) % PROBES].pos().asLong()
          : new BlockPos(1000 + i, 33, -400).asLong()});
      faults.add(skip);
    }
    root.put("Excluded", excludes);
    root.put("FaultSkipped", faults);
  }

  private static void run(ServerPlayer p, ItemStack magnet, Sources.Ref[] refs, MethodHandle base, int count) throws Throwable {
    long expected = baseline(p, magnet, refs, base);
    if (expected != indexed(p, magnet, refs)) throw new IllegalStateException("rule index changed matched sources");
    for (int pair = -1; pair < 5; pair++) {
      for (int side = 0; side < 2; side++) {
        boolean prototype = ((side + pair) & 1) != 0;
        var times = new long[32];
        for (int i = 0; i < times.length; i++) {
          long begin = System.nanoTime();
          long actual = prototype ? indexed(p, magnet, refs) : baseline(p, magnet, refs, base);
          times[i] = System.nanoTime() - begin;
          if (actual != expected) throw new IllegalStateException("candidate disagrees with baseline");
        }
        Arrays.sort(times);
        LOG.info("QA128_POLICY pair={} prototype={} perList={} checked={} p50_us={} p95_us={} matches={}",
            pair, prototype, count, refs.length, times[15]/1000.0, times[30]/1000.0, Long.bitCount(expected));
      }
    }
  }

  private static long baseline(ServerPlayer p, ItemStack magnet, Sources.Ref[] refs, MethodHandle skipped) throws Throwable {
    long matches = 0;
    for (int i = 0; i < refs.length; i++) {
      var r = refs[i];
      String key = r.key(p);
      if ((boolean)skipped.invokeExact(p, magnet, r) || Data.excluded(magnet, key)) matches |= 1L << i;
    }
    return matches;
  }

  private static long indexed(ServerPlayer p, ItemStack magnet, Sources.Ref[] refs) {
    var exclusions = Data.exclusions(magnet);
    var faults = Data.tag(magnet).getList("FaultSkipped", 10);
    if (exclusions.isEmpty() && faults.isEmpty()) {
      // 实际收取仍需一个键；只去掉两次空列表检查和重复求键。
      for (var ref : refs) java.util.Objects.requireNonNull(ref.key(p));
      return 0;
    }
    var keys = new HashSet<String>();
    var members = new LongOpenHashSet();
    String dimension = p.level().dimension().location().toString();
    for (int i = 0; i < exclusions.size(); i++) keys.add(exclusions.getCompound(i).getString("Key"));
    for (int i = 0; i < faults.size(); i++) {
      var entry = faults.getCompound(i);
      keys.add(entry.getString("Key"));
      if (dimension.equals(entry.getString("Dimension")))
        for (long pos : entry.getLongArray("Members")) members.add(pos);
    }
    long matches = 0;
    for (int i = 0; i < refs.length; i++) {
      var r = refs[i];
      if (keys.contains(r.key(p)) || !members.isEmpty() && contains(members, r.members(p))) matches |= 1L << i;
    }
    return matches;
  }

  private static boolean contains(LongOpenHashSet members, List<BlockPos> positions) {
    for (var pos : positions) if (members.contains(pos.asLong())) return true;
    return false;
  }
}
