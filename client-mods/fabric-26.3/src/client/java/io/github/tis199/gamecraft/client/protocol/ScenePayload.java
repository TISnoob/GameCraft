package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ScenePayload(String json) implements CustomPayload {
    public static final Id<ScenePayload> ID = new Id<>(Identifier.of("gamecraft", "scene"));
    public static final PacketCodec<RegistryByteBuf, ScenePayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), 32767),
            buf -> new ScenePayload(buf.readString(32767)));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
