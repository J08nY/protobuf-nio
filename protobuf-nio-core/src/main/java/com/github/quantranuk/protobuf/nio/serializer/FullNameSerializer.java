package com.github.quantranuk.protobuf.nio.serializer;

import com.github.quantranuk.protobuf.nio.ProtoSerializer;
import com.github.quantranuk.protobuf.nio.utils.ByteUtils;
import com.google.protobuf.Message;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>A serializer to serialize Protobuf messages into bytes array and deserialize bytes array back into Protobuf message</p>
 * <p>The class name of the protobuf is also serialized as part of the message. This is so that the deserialization process will be able to use the class name
 * to re-construct the protobuf message using reflection.</p>
 */
public final class FullNameSerializer implements ProtoSerializer {

    public static final int SIGNATURE = 0x7A6B5C4D;
    public static final int SIGNATURE_LENGTH = Integer.BYTES;
    public static final int PROTO_CLASSNAME_LENGTH = Integer.BYTES;
    public static final int PROTO_PAYLOAD_LENGTH = Integer.BYTES;
    public static final int HEADER_LENGTH = SIGNATURE_LENGTH + PROTO_CLASSNAME_LENGTH + PROTO_PAYLOAD_LENGTH;

    private final Charset CHARSET = StandardCharsets.ISO_8859_1;
    private final Map<ByteBuffer, Method> parseMethods = new ConcurrentHashMap<>();

    /**
     * <p>Serialize a protobuf message into byte array. The byte array will contain in this order:</p>
     * <ul>
     *     <li>Integer: A simple signature so that the deserialization can quickly detect corrupted data</li>
     *     <li>Integer: The length of the protobuf class name</li>
     *     <li>Integer: The length of the protobuf payload</li>
     *     <li>bytes[]: The decoded protobuf class name in bytes (ISO_8859_1)</li>
     *     <li>bytes[]: The protobuf payload in bytes</li>
     * </ul>
     *
     * @param message the protobuf message
     * @return serialized byte array
     */
    @Override
    public byte[] serialize(Message message) {
        ByteBuffer encodedProtobufClassName = CHARSET.encode(message.getClass().getName());
        int protobufClassNameLength = encodedProtobufClassName.capacity();

        byte[] protbufPayload = message.toByteArray();
        int protbufPayloadLength = protbufPayload.length;

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + protobufClassNameLength + protbufPayloadLength);
        buffer.putInt(SIGNATURE);
        buffer.putInt(protobufClassNameLength);
        buffer.putInt(protbufPayloadLength);
        buffer.put(encodedProtobufClassName);
        buffer.put(protbufPayload);
        return buffer.array();
    }

    @Override
    public int getSerializedSize(Message message) {
        return HEADER_LENGTH + message.getClass().getName().length() + message.getSerializedSize();
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
        return ByteUtils.readInteger(header, Integer.BYTES);
    }

    @Override
    public int extractProtobufPayloadLength(byte[] header) {
        return ByteUtils.readInteger(header, Integer.BYTES + Integer.BYTES);
    }

    @Override
    public Message deserialize(ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer) {
        final Method protobufParseMethod = getParseMethod(protobufClassNameBuffer);
        try {
            return (Message) protobufParseMethod.invoke(null, (Object) protobufPayloadBuffer);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to parse protobuf payload of " + CHARSET.decode(protobufClassNameBuffer), e);
        }
    }

    private Method getParseMethod(ByteBuffer protobufClassNameBuffer) {
        Method parseMethod = parseMethods.get(protobufClassNameBuffer);
        if (parseMethod == null) {
            String protobufClassName = CHARSET.decode(protobufClassNameBuffer).toString();
            final Class<?> protobufClass;
            try {
                protobufClass = Class.forName(protobufClassName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Invalid protobuf class name: " + protobufClassName, e);
            }

            if (!Message.class.isAssignableFrom(protobufClass)) {
                throw new IllegalStateException(protobufClassName + " is not a protobuf class");
            }

            try {
                parseMethod = protobufClass.getMethod("parseFrom", ByteBuffer.class);
                protobufClassNameBuffer.flip();
                parseMethods.put(protobufClassNameBuffer, parseMethod);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Unable to get parse method from : " + protobufClassName, e);
            }
        }
        return parseMethod;
    }

}
