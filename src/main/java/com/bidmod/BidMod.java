package com.bidmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BidMod implements ModInitializer {
	public static final String MOD_ID = "bidmod";

	static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static final Type BAL_TYPE = new TypeToken<Map<String, Long>>() {}.getType();

	/** Wird in config/bidmod.json gespeichert. */
	static class Config {
		long startingBalance = 10_000_000L;
		long minIncrement = 1L;
		int antiSnipeSeconds = 5;
		long defaultWorth = 0L;
		Map<String, Long> worth = new LinkedHashMap<>();
	}

	static class Auction {
		UUID seller; String sellerName; ItemStack stack;
		long startBid, worth, topBid; UUID topBidder; String topBidderName = "-";
		int ticksLeft;
	}

	static Config cfg = new Config();
	static Map<UUID, Long> balances = new HashMap<>();
	static final Map<UUID, List<ItemStack>> pending = new HashMap<>(); // nur im RAM
	static final ArrayDeque<Auction> queue = new ArrayDeque<>();
	static Auction current;
	static int tickCounter;

	@Override
	public void onInitialize() {
		loadConfig();
		loadBalances();
		PayloadTypeRegistry.clientboundPlay().register(AuctionPayload.TYPE, AuctionPayload.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(BidMod::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveBalances());
	}

	// ---------------------------------------------------------------- Commands

	static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("bid")
				.then(Commands.literal("start")
						.then(Commands.argument("startBid", LongArgumentType.longArg(1))
								.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
										.executes(c -> start(c.getSource().getPlayerOrException(),
												LongArgumentType.getLong(c, "startBid"),
												IntegerArgumentType.getInteger(c, "seconds"))))))
				.then(Commands.literal("cancel")
						.executes(c -> cancel(c.getSource().getPlayerOrException())))
				.then(Commands.literal("balance")
						.executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							c.getSource().sendSuccess(() -> Component.literal("Balance: " + fmt(balance(p.getUUID())))
									.withStyle(ChatFormatting.GOLD), false);
							return 1;
						}))
				.then(Commands.argument("amount", LongArgumentType.longArg(1))
						.executes(c -> bid(c.getSource().getPlayerOrException(),
								LongArgumentType.getLong(c, "amount")))));
	}

	static int fail(ServerPlayer p, String msg) {
		p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
		return 0;
	}

	static int start(ServerPlayer p, long startBid, int seconds) {
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty()) return fail(p, "Hold the item you want to auction in your main hand.");
		if (queue.size() >= 10) return fail(p, "The auction queue is full.");

		Auction a = new Auction();
		a.seller = p.getUUID();
		a.sellerName = p.getName().getString();
		a.stack = held.copy();
		a.startBid = startBid;
		a.ticksLeft = seconds * 20;
		String id = BuiltInRegistries.ITEM.getKey(a.stack.getItem()).toString();
		a.worth = cfg.worth.getOrDefault(id, cfg.defaultWorth) * a.stack.getCount();
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

		MinecraftServer server = p.level().getServer();
		if (current == null) {
			current = a;
			broadcast(server);
		} else {
			queue.add(a);
		}
		announce(server, a.sellerName + " started an auction for " + a.stack.getCount() + "x "
				+ a.stack.getHoverName().getString() + " (start bid " + fmt(startBid) + ").");
		return 1;
	}

	static int cancel(ServerPlayer p) {
		if (current == null || !current.seller.equals(p.getUUID())) return fail(p, "You have no running auction.");
		if (current.topBidder != null) return fail(p, "You can't cancel an auction that already has bids.");
		finish(p.level().getServer(), true);
		return 1;
	}

	static int bid(ServerPlayer p, long amount) {
		if (current == null) return fail(p, "There is no running auction.");
		UUID me = p.getUUID();
		if (me.equals(current.seller)) return fail(p, "You can't bid on your own auction.");
		if (me.equals(current.topBidder)) return fail(p, "You already have the top bid.");
		long need = current.topBidder == null ? current.startBid : current.topBid + cfg.minIncrement;
		if (amount < need) return fail(p, "Your bid must be at least " + fmt(need) + ".");
		if (balance(me) < amount) return fail(p, "Not enough money (balance " + fmt(balance(me)) + ").");

		if (current.topBidder != null) addBalance(current.topBidder, current.topBid); // Vorgaenger auszahlen
		addBalance(me, -amount);
		current.topBidder = me;
		current.topBidderName = p.getName().getString();
		current.topBid = amount;
		int snipe = cfg.antiSnipeSeconds * 20;
		if (current.ticksLeft < snipe) current.ticksLeft = snipe;

		MinecraftServer server = p.level().getServer();
		broadcast(server);
		announce(server, current.topBidderName + " bid " + fmt(amount) + ".");
		saveBalances();
		return 1;
	}

	// ---------------------------------------------------------------- Ablauf

	static void tick(MinecraftServer server) {
		tickCounter++;
		if (current == null && !queue.isEmpty()) {
			current = queue.poll();
			broadcast(server);
		}
		if (current != null) {
			if (--current.ticksLeft <= 0) finish(server, false);
			else if (current.ticksLeft % 20 == 0) broadcast(server);
		}
		if (tickCounter % 20 == 0) deliverPending(server);
	}

	static void finish(MinecraftServer server, boolean cancelled) {
		Auction a = current;
		current = null;
		String name = a.stack.getHoverName().getString();
		if (a.topBidder == null || cancelled) {
			give(server, a.seller, a.stack);
			announce(server, "Auction for " + name + " ended without a winner.");
		} else {
			addBalance(a.seller, a.topBid);
			give(server, a.topBidder, a.stack);
			announce(server, a.topBidderName + " won " + name + " for " + fmt(a.topBid) + "!");
		}
		saveBalances();
		broadcast(server);
	}

	static void give(MinecraftServer server, UUID uuid, ItemStack stack) {
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		if (p != null) p.getInventory().placeItemBackInInventory(stack);
		else pending.computeIfAbsent(uuid, k -> new ArrayList<>()).add(stack);
	}

	static void deliverPending(MinecraftServer server) {
		if (pending.isEmpty()) return;
		Iterator<Map.Entry<UUID, List<ItemStack>>> it = pending.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, List<ItemStack>> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) continue;
			e.getValue().forEach(s -> p.getInventory().placeItemBackInInventory(s));
			p.sendSystemMessage(Component.literal("You received your auction items.").withStyle(ChatFormatting.GREEN));
			it.remove();
		}
	}

	static void broadcast(MinecraftServer server) {
		AuctionPayload payload = current == null ? AuctionPayload.none()
				: new AuctionPayload(current.stack, current.worth, current.topBid, current.topBidderName,
						current.topBidder == null ? current.startBid : cfg.minIncrement, current.ticksLeft);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, payload);
	}

	static void announce(MinecraftServer server, String msg) {
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[Auction] ").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(Component.literal(msg).withStyle(ChatFormatting.WHITE)), false);
	}

	// ---------------------------------------------------------------- Geld & Dateien

	static long balance(UUID id) { return balances.computeIfAbsent(id, k -> cfg.startingBalance); }
	static void addBalance(UUID id, long delta) { balances.put(id, balance(id) + delta); }
	public static String fmt(long n) { return String.format(Locale.US, "%,d", n); }

	static Path dir() { return FabricLoader.getInstance().getConfigDir(); }

	static void loadConfig() {
		Path f = dir().resolve("bidmod.json");
		try {
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f)) { cfg = GSON.fromJson(r, Config.class); }
			} else {
				cfg.worth.put("minecraft:netherite_block", 4_600_000L); // Beispielwerte
				cfg.worth.put("minecraft:netherite_ingot", 510_000L);
				cfg.worth.put("minecraft:diamond", 10_000L);
				try (Writer w = Files.newBufferedWriter(f)) { GSON.toJson(cfg, w); }
			}
		} catch (Exception e) { e.printStackTrace(); }
	}

	static void loadBalances() {
		Path f = dir().resolve("bidmod-balances.json");
		try {
			if (!Files.exists(f)) return;
			try (Reader r = Files.newBufferedReader(f)) {
				Map<String, Long> raw = GSON.fromJson(r, BAL_TYPE);
				if (raw != null) raw.forEach((k, v) -> balances.put(UUID.fromString(k), v));
			}
		} catch (Exception e) { e.printStackTrace(); }
	}

	static void saveBalances() {
		Map<String, Long> raw = new HashMap<>();
		balances.forEach((k, v) -> raw.put(k.toString(), v));
		try (Writer w = Files.newBufferedWriter(dir().resolve("bidmod-balances.json"))) {
			GSON.toJson(raw, BAL_TYPE, w);
		} catch (Exception e) { e.printStackTrace(); }
	}
}
