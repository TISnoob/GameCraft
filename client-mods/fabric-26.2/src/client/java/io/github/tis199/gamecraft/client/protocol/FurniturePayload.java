package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FurniturePayload(String json) implements CustomPacketPayload {
    public static final Type<FurniturePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gamecraft", "furniture"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FurniturePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json()), buf -> new FurniturePayload(buf.readUtf(32767)));

    @Override public Type<? extends CustomPacketPayload> type() { return ID; }
}
