package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ScenePayload(String json) implements CustomPacketPayload {
    public static final Type<ScenePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gamecraft", "scene"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScenePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.json()), buf -> new ScenePayload(buf.readUtf(32767)));

    @Override public Type<? extends CustomPacketPayload> type() { return ID; }
}
