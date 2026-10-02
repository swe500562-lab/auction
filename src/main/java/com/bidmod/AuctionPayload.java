package com.bidmod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Server -> Client: aktueller Auktionsstand. remainingTicks < 0 = keine Auktion. */
public record AuctionPayload(ItemStack stack, long worth, long topBid, String topBidder,
                             long minimum, int remainingTicks) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<AuctionPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BidMod.MOD_ID, "auction"));

	public static final StreamCodec<RegistryFriendlyByteBuf, AuctionPayload> CODEC = StreamCodec.composite(
			ItemStack.OPTIONAL_STREAM_CODEC, AuctionPayload::stack,
			ByteBufCodecs.VAR_LONG, AuctionPayload::worth,
			ByteBufCodecs.VAR_LONG, AuctionPayload::topBid,
			ByteBufCodecs.STRING_UTF8, AuctionPayload::topBidder,
			ByteBufCodecs.VAR_LONG, AuctionPayload::minimum,
			ByteBufCodecs.VAR_INT, AuctionPayload::remainingTicks,
			AuctionPayload::new);

	public static AuctionPayload none() {
		return new AuctionPayload(ItemStack.EMPTY, 0, 0, "", 0, -1);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
