package dev.chestmagnet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

final class TrackingToast implements Toast {
  private static final String TITLE = "宝藏磁铁追踪提醒";
  private final String token;
  private final ItemStack icon;
  private final String name;
  private final boolean destroyed;
  private long amount, last, changed;
  private boolean sounded;

  private TrackingToast(String token, Net.TrackingAlert m) {
    this.token = token;
    icon = TrackingPanel.icon(m.display());
    name = MagnetScreen.itemName(m.display(), icon);
    amount = m.amount();
    destroyed = m.destroyed();
  }

  static void receive(Net.TrackingAlert m) {
    if (m.display() == null || m.amount() <= 0) return;
    var toasts = net.minecraft.client.Minecraft.getInstance().getToasts();
    String token = m.rule() + (m.destroyed() ? "#destroyed" : "#stored");
    var previous = toasts.getToast(TrackingToast.class, token);
    if (previous != null) {
      previous.amount = Math.min(Integer.MAX_VALUE, previous.amount + m.amount());
      previous.changed = previous.last;
    } else toasts.addToast(new TrackingToast(token, m));
  }

  @Override public Object getToken() { return token; }

  @Override public Visibility render(GuiGraphics g, ToastComponent owner, long age) {
    last = age;
    g.blit(TEXTURE, 0, 0, 0, 0, width(), height());
    g.renderFakeItem(icon, 8, 8);
    var client = owner.getMinecraft();
    var font = client.font;
    g.drawString(font, TITLE, 30, 6, ChatFormatting.GOLD.getColor(), false);
    String suffix = destroyed ? " 被销毁" : " 已获取";
    String text = destroyed ? "追踪的物品被销毁" : name + suffix;
    if (amount > 1) text += " ×" + amount;
    if (!destroyed && font.width(text) > width() - 34) {
      String receipt = suffix + (amount > 1 ? " ×" + amount : "");
      text = font.plainSubstrByWidth(name, width() - 38 - font.width(receipt)) + "…" + receipt;
    }
    g.drawString(font, font.plainSubstrByWidth(text, width() - 34), 30, 18,
        destroyed ? 0xFFB7A5 : 0xFFFFFF, false);
    if (!sounded) {
      sounded = true;
      client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1, 1));
    }
    return age - Math.min(changed, 3000) >= 5000.0 * client.options.notificationDisplayTime().get()
        ? Visibility.HIDE : Visibility.SHOW;
  }
}
