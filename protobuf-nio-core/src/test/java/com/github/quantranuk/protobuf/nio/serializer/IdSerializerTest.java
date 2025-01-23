package com.github.quantranuk.protobuf.nio.serializer;

import com.github.quantranuk.protobuf.nio.proto.TestHeartBeat;
import com.google.protobuf.Message;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IdSerializerTest {

    @Test
    public void testRoundTripSerialization() {
        long requestTimeMillis = System.currentTimeMillis();
        String requestMessage = "HB_REQUEST_" + requestTimeMillis;

        TestHeartBeat.HeartBeatRequest message = TestHeartBeat.HeartBeatRequest.newBuilder()
                .setRequestTimeMillis(requestTimeMillis)
                .setRequestMessage(requestMessage)
                .build();
        Map<Class<? extends Message>, Integer> idMap = Map.of(TestHeartBeat.HeartBeatRequest.class, 1);
        IdSerializer serializer = new IdSerializer(idMap);

        byte[] serializedBytes = serializer.serialize(message);
        System.out.println("Serialized bytes: " + serializedBytes.length);

        byte[] header = new byte[serializer.getHeaderLength()];
        ByteBuffer serializedByteBuffer = ByteBuffer.wrap(serializedBytes);
        serializedByteBuffer.get(header);
        int protobufClassnameLength = serializer.extractProtobufClassnameLength(header);
        int protobufPayloadLength = serializer.extractProtobufPayloadLength(header);

        assertEquals(message.getSerializedSize(), protobufPayloadLength);

        byte[] protobufClassNameBytes = new byte[protobufClassnameLength];
        byte[] protobufPayloadBytes = new byte[protobufPayloadLength];
        serializedByteBuffer.get(protobufClassNameBytes);
        serializedByteBuffer.get(protobufPayloadBytes);

        Message deserializedMessage = serializer.deserialize(ByteBuffer.wrap(protobufClassNameBytes), ByteBuffer.wrap(protobufPayloadBytes));
        assertTrue(deserializedMessage instanceof TestHeartBeat.HeartBeatRequest);

        assertEquals(requestTimeMillis, ((TestHeartBeat.HeartBeatRequest) deserializedMessage).getRequestTimeMillis());
        assertEquals(requestMessage, ((TestHeartBeat.HeartBeatRequest) deserializedMessage).getRequestMessage());
    }

}
