package io.github.tis199.gamecraft.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Small versioned payload for Paper-to-proxy requests carried by plugin messaging. */
public record GameCraftBridgeMessage(Type type, UUID playerId, String requestId, String payload) {
    public static final String CHANNEL = "gamecraft:bridge";
    public static final int VERSION = 1;
    private static final int MAX_TEXT_LENGTH = 16_000;

    public enum Type {
        MENU_OPEN,
        MENU_OPENED,
        MENU_FALLBACK,
        MENU_SELECTED,
        MENU_CLOSED,
        PING,
        PONG
    }

    public GameCraftBridgeMessage {
        if (type == null || playerId == null || requestId == null || payload == null) {
            throw new IllegalArgumentException("Bridge message fields must not be null");
        }
        if (requestId.length() > 64 || payload.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Bridge message is too large");
        }
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeByte(VERSION);
            output.writeByte(type.ordinal());
            output.writeLong(playerId.getMostSignificantBits());
            output.writeLong(playerId.getLeastSignificantBits());
            output.writeUTF(requestId);
            output.writeUTF(payload);
        }
        return buffer.toByteArray();
    }

    public static GameCraftBridgeMessage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_TEXT_LENGTH * 2) {
            throw new IOException("Invalid GameCraft bridge message size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported GameCraft bridge protocol version: " + version);
            }
            int typeIndex = input.readUnsignedByte();
            if (typeIndex >= Type.values().length) {
                throw new IOException("Unknown GameCraft bridge message type");
            }
            UUID playerId = new UUID(input.readLong(), input.readLong());
            String requestId = input.readUTF();
            String payload = input.readUTF();
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing bridge payload");
            }
            return new GameCraftBridgeMessage(Type.values()[typeIndex], playerId, requestId, payload);
        }
    }
}
