package io.github.tis199.gamecraft.client.protocol;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ClientPayload(String json) implements CustomPayload {
    public static final Id<ClientPayload> ID = new Id<>(Identifier.of("gamecraft", "client"));
    public static final PacketCodec<RegistryByteBuf, ClientPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeString(payload.json(), 4096),
            buf -> new ClientPayload(buf.readString(4096)));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
