package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record FurniturePayload(String json) implements CustomPayload {
    public static final Id<FurniturePayload> ID = new Id<>(Identifier.of("gamecraft", "furniture"));
    public static final PacketCodec<RegistryByteBuf, FurniturePayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), 32767),
            buf -> new FurniturePayload(buf.readString(32767)));

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
