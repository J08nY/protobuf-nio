package com.quantran.protobuf.nio.impl;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.serializer.ProtobufSerializer;
import com.quantran.protobuf.nio.utils.ByteArrayStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

class SocketChannelReader implements CompletionHandler<Integer, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketChannelReader.class);

    private final ByteBuffer readBuffer;
    private final byte[] header;
    private final ByteArrayStack readStack;
    private final AsynchronousSocketChannel socketChannel;
    private final SocketAddress socketAddress;
    private final CompletionHandler<Long, Message> messageReadCompletionHandler;
    private final long readTimeoutMillis;
    private final ExecutorService readExecutor;
    private int protobufClassNameLength;
    private int protobufPayloadLength;

    private enum ReadState {READING_MESSAGE_HEADER, READING_MESSAGE_BODY, STOPPED}
    private ReadState readState;

    SocketChannelReader(AsynchronousSocketChannel socketChannel, SocketAddress socketAddress, long readTimeoutMillis, int readBufferCapacity, ExecutorService readExecutor, CompletionHandler<Long, Message> messageReadCompletionHandler) {
        this.socketChannel = socketChannel;
        this.socketAddress = socketAddress;
        this.readExecutor = readExecutor;
        this.readTimeoutMillis = readTimeoutMillis;
        this.readBuffer = ByteBuffer.allocate(readBufferCapacity);
        this.header = new byte[ProtobufSerializer.HEADER_LENGTH];
        this.readStack = new ByteArrayStack();
        this.messageReadCompletionHandler = messageReadCompletionHandler;
    }

    void start() {
        readState = ReadState.READING_MESSAGE_HEADER;
        readExecutor.execute(this::readNextBlock);
    }

    void stop() {
        readState = ReadState.STOPPED;
    }

    private void readNextBlock() {
        if (readState == ReadState.STOPPED || !socketChannel.isOpen()) {
            return;
        }
        readBuffer.clear();
        if (readTimeoutMillis == 0) {
            socketChannel.read(readBuffer, null, this);
        } else {
            socketChannel.read(readBuffer, readTimeoutMillis, TimeUnit.MILLISECONDS, null, this);
        }
    }

    @Override
    public void completed(Integer readLength, Object attachment) {
        readExecutor.execute(() -> {
            if (readLength == -1) {
                failed(new IllegalStateException("Reached end-of-stream of " + socketAddress), null);
            } else if (readLength > 0) {
                readBuffer.flip();
                readStack.push(readBuffer.array(), readBuffer.position(), readBuffer.limit());

                boolean hasRemainingData = true;
                while(hasRemainingData) {
                    switch (readState) {
                        case READING_MESSAGE_HEADER:
                            hasRemainingData = processHeader();
                            break;
                        case READING_MESSAGE_BODY:
                            hasRemainingData = processBody();
                            break;
                        case STOPPED:
                            hasRemainingData = false;
                            break;
                        default:
                            hasRemainingData = false;
                    }
                }

                readNextBlock();
            }
        });
    }

    private boolean processHeader() {
        if (readStack.getRemaining() < header.length) {
            return false;
        }
        readStack.popExactly(header);
        if (ProtobufSerializer.hasValidHeaderSignature(header)) {
            protobufClassNameLength = ProtobufSerializer.extractProtobufClassnameLength(header);
            protobufPayloadLength = ProtobufSerializer.extractProtobufPayloadLength(header);
            readState = ReadState.READING_MESSAGE_BODY;
            return true;
        } else {
            failed(new IllegalStateException("Invalid header read"), null);
            return false;
        }
    }

    private boolean processBody() {
        if (readStack.getRemaining() < protobufClassNameLength + protobufPayloadLength) {
            return false;
        }
        ByteBuffer protobufClassNameBytes = readStack.popExactly(protobufClassNameLength);
        ByteBuffer protobufPayloadBytes = readStack.popExactly(protobufPayloadLength);
        Message message = ProtobufSerializer.deserialize(protobufClassNameBytes, protobufPayloadBytes);
        messageReadCompletionHandler.completed((long) protobufPayloadLength, message);
        readState = ReadState.READING_MESSAGE_HEADER;
        return true;
    }

    @Override
    public void failed(Throwable t, Object attachment) {
        readState = ReadState.STOPPED;
        messageReadCompletionHandler.failed(t, null);
    }

}
