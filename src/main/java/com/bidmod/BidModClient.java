package com.bidmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class BidModClient implements ClientModInitializer {
	static volatile AuctionPayload current;
	static volatile long endMillis;
	static ItemStack CLOCK; // erst beim ersten Zeichnen erzeugen (Items sind beim Start noch nicht bereit)

	static final int BG = 0xF0101820, BORDER = 0xFF2F6FD0, SLOT = 0xFF2B2B2B, WHITE = 0xFFFFFFFF,
			CYAN = 0xFF55FFFF, GOLD = 0xFFFFAA00, PURPLE = 0xFFB84DFF, GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(AuctionPayload.TYPE, (payload, context) -> {
			endMillis = System.currentTimeMillis() + payload.remainingTicks() * 50L;
			current = payload.remainingTicks() < 0 ? null : payload;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = null);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(BidMod.MOD_ID, "auction"), BidModClient::render);
	}

	static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		AuctionPayload a = current;
		if (a == null || a.stack().isEmpty()) return;

		if (CLOCK == null) CLOCK = new ItemStack(Items.CLOCK);
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		final int w = 230, h = 64;
		int x = (mc.getWindow().getGuiScaledWidth() - w) / 2, y = 6;

		// Panel + Rahmen
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
		g.fill(x, y, x + w, y + h, BG);

		// Item-Slot links
		g.fill(x + 6, y + 18, x + 34, y + 46, SLOT);
		g.item(a.stack(), x + 12, y + 24);

		// Titel + Trennlinie
		Component title = a.stack().getHoverName().copy().withStyle(ChatFormatting.BOLD);
		g.text(font, title, x + 42, y + 5, WHITE);
		g.fill(x + 42, y + 16, x + w - 74, y + 17, 0x55FFFFFF);

		// Zeilen
		int lx = x + 42, vx = x + 96;
		boolean noBid = a.topBid() <= 0;
		g.text(font, "Worth:", lx, y + 21, WHITE);
		g.text(font, BidMod.fmt(a.worth()), vx, y + 21, CYAN);
		g.text(font, "Top Bid:", lx, y + 31, WHITE);
		g.text(font, noBid ? "-" : BidMod.fmt(a.topBid()), vx, y + 31, GOLD);
		g.text(font, noBid ? "-" : a.topBidder(), lx, y + 41, PURPLE);
		g.text(font, "Minimum:", lx, y + 51, WHITE);
		g.text(font, BidMod.fmt(a.minimum()), vx, y + 51, GREEN);

		// Trenner + Zeit rechts
		int rx = x + w - 66, cx = x + w - 33;
		g.fill(rx - 4, y + 10, rx - 3, y + h - 8, 0x55FFFFFF);
		g.item(CLOCK, cx - 8, y + 8);
		centered(g, font, "Time Left", cx, y + 30, WHITE);
		centered(g, font, timeString(), cx, y + 44, YELLOW);
	}

	static String timeString() {
		long s = (Math.max(0, endMillis - System.currentTimeMillis()) + 999) / 1000;
		return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
	}

	static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, color);
	}
}
