package com.github.quantranuk.protobuf.nio;

import com.google.protobuf.Message;

import java.nio.ByteBuffer;

/**
 * The interface to serialize and deserialize protobuf messages, along with encoding their length and class name.
 */
public interface ProtoSerializer {
    /**
     * Serialize a protobuf message into byte array.
     *
     * @param message the protobuf message
     * @return serialized byte array
     */
    byte[] serialize(Message message);

    /**
     * Get the size (in number of bytes) of a fully serialized protobuf message, including all the header information.
     *
     * @param message the protobuf message
     * @return the size of a fully serialized message in bytes (including the header size)
     */
    int getSerializedSize(Message message);

    /**
     * Get the length of the header.
     *
     * @return the length of the header
     */
    int getHeaderLength();

    /**
     * Check if the header started with a valid signature
     *
     * @param header the message header
     * @return true if the signature if the header is valid
     */
    boolean hasValidHeaderSignature(byte[] header);

    /**
     * Get the length of the protobuf class name
     *
     * @param header the message header
     * @return the length of the protobuf class name
     */
    int extractProtobufClassnameLength(byte[] header);

    /**
     * Get the length of the protobuf payload
     *
     * @param header the message header
     * @return the length of the protobuf payload
     */
    int extractProtobufPayloadLength(byte[] header);

    /**
     * Deserialized a protobuf message using protobuf payload and the class name information
     *
     * @param protobufClassNameBuffer the buffer that contains the class name of the protobuf
     * @param protobufPayloadBuffer   the buffer that contains the protobuf payload
     * @return the protobuf message
     */
    Message deserialize(ByteBuffer protobufClassNameBuffer, ByteBuffer protobufPayloadBuffer);
}
