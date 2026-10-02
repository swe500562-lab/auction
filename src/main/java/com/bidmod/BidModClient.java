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
	static ItemStack CLOCK;

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
		if (a == null || a.stack().isEmpty())
