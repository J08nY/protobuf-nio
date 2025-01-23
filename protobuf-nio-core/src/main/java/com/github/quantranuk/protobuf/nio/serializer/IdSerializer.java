package com.github.quantranuk.protobuf.nio.serializer;

import com.github.quantranuk.protobuf.nio.ProtoSerializer;
import com.github.quantranuk.protobuf.nio.utils.ByteUtils;
import com.google.protobuf.Message;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IdSerializer implements ProtoSerializer {
    private final Map<Integer, Method> parseMethods = new ConcurrentHashMap<>();
    private final Map<Class<? extends Message>, Integer> idMap = new ConcurrentHashMap<>();

    public static final int SIGNATURE = 0x58476215;
    public static final int SIGNATURE_LENGTH = Integer.BYTES;
    public static final int PROTO_PAYLOAD_LENGTH = Integer.BYTES;
    public static final int HEADER_LENGTH = SIGNATURE_LENGTH + PROTO_PAYLOAD_LENGTH;

    public IdSerializer(Map<Class<? extends Message>, Integer> idMap) {
        for (Map.Entry<Class<? extends Message>, Integer> entry : idMap.entrySet()) {
            this.idMap.put(entry.getKey(), entry.getValue());
            try {
                Method parseMethod = entry.getKey().getMethod("parseFrom", ByteBuffer.class);
                parseMethods.put(entry.getValue(), parseMethod);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("The class " + entry.getKey().getName() + " does not have a parseFrom(byte[]) method");
            }
        }
    }

    @Override
    public byte[] serialize(Message message) {
        byte[] protobufPayload = message.toByteArray();
        int protobufPayloadLength = protobufPayload.length;

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + Integer.BYTES + protobufPayloadLength);
        buffer.putInt(SIGNATURE);
        buffer.putInt(protobufPayloadLength);
        buffer.putInt(idMap.get(message.getClass()));
        buffer.put(protobufPayload);
        return buffer.array();
    }

    @Override
    public int getSerializedSize(Message message) {
        return HEADER_LENGTH + Integer.BYTES + message.getSerializedSize();
    }

    @Override
    public int getHeaderLength() {
        return HEADER_LENGTH;
    }

    @Override
    public boolean hasValidHeaderSignature(byte[] header) {
        return ByteUtils.readInteger(header, 0) == SIGNATURE;
    }

    @Override
    public int extractProtobufClassnameLength(byte[] header) {
        return Integer.BYTES;
    }

    @Override
    public int extractProtobufPayloadLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES);
    }

    @Override
    public Message deserialize(ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer) {
        int messageId = protobufClassNameBuffer.getInt();
        Method parseMethod = parseMethods.get(messageId);
        try {
            return (Message) parseMethod.invoke(null, protobufPayloadBuffer);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to parse protobuf payload of " + messageId, e);
        }
    }
}
