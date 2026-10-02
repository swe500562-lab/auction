package com.bidmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reine Client-Mod: Die Karte wird nur bei dir angezeigt und per /bidhud gesteuert. */
public class BidModClient implements ClientModInitializer {
	static final String MOD_ID = "bidmod";

	// Zustand (alles nur lokal)
	static ItemStack stack;            // null = keine Karte
	static long worth, topBid, minimum = 1;
	static String bidder = "-";
	static long endMillis;
	static ItemStack clock;
	static boolean auto = true;        // Gebote automatisch aus dem Chat lesen

	// "Du hast $25,000 von ryx77 erhalten."  /  "You received $25,000 from ryx77"
	static final Pattern[] PAY = {
			Pattern.compile("Du hast \\$([0-9][0-9.,]*[kKmMbB]?) von (\\w+) erhalten"),
			Pattern.compile("You (?:have )?received \\$([0-9][0-9.,]*[kKmMbB]?) from (\\w+)")
	};

	static final int BG = 0xF0101820, BORDER = 0xFF2F6FD0, SLOT = 0xFF2B2B2B, WHITE = 0xFFFFFFFF,
			CYAN = 0xFF55FFFF, GOLD = 0xFFFFAA00, PURPLE = 0xFFB84DFF, GREEN = 0xFF55FF55,
			YELLOW = 0xFFFFFF55, RED = 0xFFFF5555;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("bidhud")
						.executes(c -> help(c.getSource()))
						.then(ClientCommands.literal("start")
								.then(ClientCommands.argument("worth", StringArgumentType.word())
										.executes(c -> start(c.getSource(), StringArgumentType.getString(c, "worth"), 60))
										.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 86400))
												.executes(c -> start(c.getSource(), StringArgumentType.getString(c, "worth"),
														IntegerArgumentType.getInteger(c, "seconds"))))))
						.then(ClientCommands.literal("worth")
								.then(ClientCommands.argument("amount", StringArgumentType.word())
										.executes(c -> { if (!active(c.getSource())) return 0;
											worth = parse(StringArgumentType.getString(c, "amount"));
											return ok(c.getSource(), "Worth: " + fmt(worth)); })))
						.then(ClientCommands.literal("bid")
								.then(ClientCommands.argument("amount", StringArgumentType.word())
										.executes(c -> bid(c.getSource(), StringArgumentType.getString(c, "amount"), null))
										.then(ClientCommands.argument("name", StringArgumentType.word())
												.executes(c -> bid(c.getSource(), StringArgumentType.getString(c, "amount"),
														StringArgumentType.getString(c, "name"))))))
						.then(ClientCommands.literal("min")
								.then(ClientCommands.argument("amount", StringArgumentType.word())
										.executes(c -> { if (!active(c.getSource())) return 0;
											minimum = parse(StringArgumentType.getString(c, "amount"));
											return ok(c.getSource(), "Minimum: " + fmt(minimum)); })))
						.then(ClientCommands.literal("time")
								.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0, 86400))
										.executes(c -> { if (!active(c.getSource())) return 0;
											endMillis = System.currentTimeMillis() + IntegerArgumentType.getInteger(c, "seconds") * 1000L;
											return ok(c.getSource(), "Zeit gesetzt."); })))
						.then(ClientCommands.literal("item")
								.executes(c -> { if (!active(c.getSource())) return 0; return takeItem(c.getSource()); }))
						.then(ClientCommands.literal("auto")
								.executes(c -> { auto = !auto;
									return ok(c.getSource(), "Automatisch mitlesen: " + (auto ? "AN" : "AUS")); }))
						.then(ClientCommands.literal("clear")
								.executes(c -> { stack = null; return ok(c.getSource(), "Karte ausgeblendet."); }))));

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onServerMessage(message.getString()));

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "auction"), BidModClient::render);
	}

	// ------------------------------------------------------------ Befehle

	static int ok(FabricClientCommandSource s, String msg) {
		s.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GREEN));
		return 1;
	}

	static int err(FabricClientCommandSource s, String msg) {
		s.sendError(Component.literal(msg));
		return 0;
	}

	static boolean active(FabricClientCommandSource s) {
		if (stack == null) { err(s, "Zuerst: /bidhud start <wert> [sekunden] (mit Item in der Hand)"); return false; }
		return true;
	}

	static int help(FabricClientCommandSource s) {
		s.sendFeedback(Component.literal(
				"/bidhud start <wert> [sek]  - Karte starten (Item in der Hand)\n"
				+ "/bidhud worth <wert>  - Wert aendern\n"
				+ "/bidhud bid <betrag> [name]  - Hoechstgebot setzen\n"
				+ "/bidhud min <betrag>  - Minimum setzen\n"
				+ "/bidhud time <sek>  - Restzeit setzen\n"
				+ "/bidhud item  - Item aus der Hand uebernehmen\n"
				+ "/bidhud auto  - Gebote aus dem Chat lesen an/aus\n"
				+ "/bidhud clear  - Karte ausblenden\n"
				+ "Zahlen gehen auch als 4.6m, 300k, 1b oder 4,600,000.").withStyle(ChatFormatting.AQUA));
		return 1;
	}

	static int takeItem(FabricClientCommandSource s) {
		ItemStack held = s.getPlayer().getMainHandItem();
		if (held.isEmpty()) return err(s, "Du hast kein Item in der Hand.");
		stack = held.copy();
		return ok(s, "Item: " + stack.getHoverName().getString());
	}

	static int start(FabricClientCommandSource s, String worthText, int seconds) throws CommandSyntaxException {
		long w = parse(worthText);
		ItemStack held = s.getPlayer().getMainHandItem();
		if (held.isEmpty()) return err(s, "Nimm das Item in die Hand, das versteigert wird.");
		stack = held.copy();
		worth = w;
		topBid = 0;
		bidder = "-";
		minimum = 1;
		endMillis = System.currentTimeMillis() + seconds * 1000L;
		return ok(s, "Karte gestartet: " + stack.getHoverName().getString() + ", Wert " + fmt(w) + ", " + seconds + "s");
	}

	static int bid(FabricClientCommandSource s, String amountText, String name) throws CommandSyntaxException {
		if (!active(s)) return 0;
		topBid = parse(amountText);
		if (name != null) bidder = name;
		else if (bidder.equals("-")) bidder = "Unknown";
		return ok(s, "Top Bid: " + fmt(topBid) + " (" + bidder + ")");
	}

	/** Versteht 4600000, 4,600,000, 4.6m, 300k, 1b. */
	static long parse(String text) throws CommandSyntaxException {
		String t = text.replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
		double mult = 1;
		if (t.endsWith("k")) { mult = 1e3; t = t.substring(0, t.length() - 1); }
		else if (t.endsWith("m")) { mult = 1e6; t = t.substring(0, t.length() - 1); }
		else if (t.endsWith("b")) { mult = 1e9; t = t.substring(0, t.length() - 1); }
		try {
			double v = Double.parseDouble(t) * mult;
			if (v < 0) throw new NumberFormatException();
			return Math.round(v);
		} catch (NumberFormatException e) {
			throw new SimpleCommandExceptionType(Component.literal("Ungueltige Zahl: " + text)).create();
		}
	}

	static String fmt(long n) { return String.format(Locale.US, "%,d", n); }

	/** Liest Zahlungen aus dem Chat: "Du hast $X von NAME erhalten" = neues Gebot, wenn hoeher. */
	static void onServerMessage(String text) {
		if (!auto || stack == null || System.currentTimeMillis() >= endMillis) return;
		for (Pattern p : PAY) {
			Matcher m = p.matcher(text);
			if (!m.find()) continue;
			try {
				long amount = parse(m.group(1));
				if (amount > topBid) {
					topBid = amount;
					bidder = m.group(2);
				}
			} catch (Exception ignored) { }
			return;
		}
	}

	// ------------------------------------------------------------ HUD

	static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		ItemStack st = stack;
		if (st == null || st.isEmpty()) return;

		if (clock == null) clock = new ItemStack(Items.CLOCK);
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		final int w = 230, h = 64;
		int x = (mc.getWindow().getGuiScaledWidth() - w) / 2, y = 6;

		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
		g.fill(x, y, x + w, y + h, BG);

		g.fill(x + 6, y + 18, x + 34, y + 46, SLOT);
		g.item(st, x + 12, y + 24);

		Component title = st.getHoverName().copy().withStyle(ChatFormatting.BOLD);
		g.text(font, title, x + 42, y + 5, WHITE);
		g.fill(x + 42, y + 16, x + w - 74, y + 17, 0x55FFFFFF);

		int lx = x + 42, vx = x + 96;
		boolean noBid = topBid <= 0;
		g.text(font, "Worth:", lx, y + 21, WHITE);
		g.text(font, fmt(worth), vx, y + 21, CYAN);
		g.text(font, "Top Bid:", lx, y + 31, WHITE);
		g.text(font, noBid ? "-" : fmt(topBid), vx, y + 31, GOLD);
		g.text(font, noBid ? "-" : bidder, lx, y + 41, PURPLE);
		g.text(font, "Minimum:", lx, y + 51, WHITE);
		g.text(font, fmt(minimum), vx, y + 51, GREEN);

		long ms = endMillis - System.currentTimeMillis();
		boolean ended = ms <= 0;
		long s = (Math.max(0, ms) + 999) / 1000;
		String time = ended ? "Ended" : (s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s");

		int rx = x + w - 66, cx = x + w - 33;
		g.fill(rx - 4, y + 10, rx - 3, y + h - 8, 0x55FFFFFF);
		g.item(clock, cx - 8, y + 8);
		centered(g, font, "Time Left", cx, y + 30, WHITE);
		centered(g, font, time, cx, y + 44, ended ? RED : YELLOW);
	}

	static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
		g.text(font, text, cx - font.width(text) / 2, y, color);
	}
}
