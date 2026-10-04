package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ClientPayload(String json) implements CustomPacketPayload {
    public static final Type<ClientPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gamecraft", "client"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json()), buf -> new ClientPayload(buf.readUtf(4096)));

    @Override public Type<? extends CustomPacketPayload> type() { return ID; }
}
