package sk.neuromancer.protobuf.nio.impl;

import sk.neuromancer.protobuf.nio.serializer.ProtobufSerializer;
import sk.neuromancer.protobuf.nio.utils.ByteArrayDequeue;
import com.google.protobuf.GeneratedMessage;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class SocketChannelWriter implements CompletionHandler<Integer, List<GeneratedMessage>> {

    private final AsynchronousSocketChannel socketChannel;
    private final CompletionHandler<Long, GeneratedMessage> messageWriteCompletionHandler;
    private final ExecutorService writeExecutor;
    private final Queue<GeneratedMessage> outboundMessageQueue;
    private final long writeTimeoutMillis;
    private final ByteArrayDequeue writeBytesQueue;
    private final ByteBuffer writeBuffer;
    private final int writeBufferCapacity;
    private final List<GeneratedMessage> messagesBeingWritten;
    private final AtomicBoolean isWritingInProgress;
    private final int maxMessageWriteQueueSize;

    SocketChannelWriter(AsynchronousSocketChannel socketChannel, long writeTimeoutMillis, int writeBufferCapacity, int maxMessageWriteQueueSize, ExecutorService writeExecutor, CompletionHandler<Long, GeneratedMessage> messageWriteCompletionHandler) {
        this.socketChannel = socketChannel;
        this.maxMessageWriteQueueSize = maxMessageWriteQueueSize;
        this.messageWriteCompletionHandler = messageWriteCompletionHandler;
        this.writeExecutor = writeExecutor;
        this.outboundMessageQueue = new ArrayDeque<>();
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.isWritingInProgress = new AtomicBoolean();
        this.writeBytesQueue = new ByteArrayDequeue();
        this.writeBufferCapacity = writeBufferCapacity;
        this.messagesBeingWritten = new ArrayList<>();
        this.writeBuffer = ByteBuffer.allocate(writeBufferCapacity);
    }

    void addToWriteQueue(GeneratedMessage message) {
        if (outboundMessageQueue.size() > maxMessageWriteQueueSize) {
            throw new IllegalStateException("Unable to accept more message due to outbound message queue is too large (" + outboundMessageQueue.size() + ")");
        }

        writeExecutor.execute(() -> {
            outboundMessageQueue.add(message);
            if (!isWritingInProgress.getAndSet(true)) {
                checkMessageQueue();
            }
        });
    }

    private void checkMessageQueue() {
        pollNextBatch();
        if (messagesBeingWritten.isEmpty()) {
            isWritingInProgress.set(false);
            return;
        }
        writeMessages(messagesBeingWritten);
    }

    private void pollNextBatch() {
        int bytesToWrite = 0;
        int nextMessageSize = 0;
        messagesBeingWritten.clear();
        while (bytesToWrite + nextMessageSize < writeBufferCapacity) {
            GeneratedMessage message = outboundMessageQueue.poll();
            if (message == null) {
                break;
            }
            messagesBeingWritten.add(message);
            bytesToWrite += ProtobufSerializer.getSerializedSize(message);
            nextMessageSize = outboundMessageQueue.isEmpty() ? 0 : ProtobufSerializer.getSerializedSize(outboundMessageQueue.peek());
        }
    }

    private void writeMessages(List<GeneratedMessage> messages) {
        writeBytesQueue.clear();
        messages.forEach(message -> writeBytesQueue.push(ProtobufSerializer.serialize(message)));
        writeNextBlock(messages);
    }

    private void writeNextBlock(List<GeneratedMessage> messages) {
        ByteBuffer nextBlock = writeBytesQueue.popMaximum(writeBufferCapacity);
        if (nextBlock == null) {
            messages.forEach(message -> messageWriteCompletionHandler.completed((long) ProtobufSerializer.getSerializedSize(message), message));
            checkMessageQueue();
            return;
        }

        writeBuffer.clear();
        writeBuffer.put(nextBlock);
        writeBuffer.flip();
        socketChannel.write(writeBuffer, writeTimeoutMillis, TimeUnit.MILLISECONDS, messages, this);
    }

    @Override
    public void completed(Integer result, List<GeneratedMessage> messages) {
        writeExecutor.execute(() -> {
            int unwrittenBytes = writeBuffer.limit() - result;
            if (unwrittenBytes > 0) {
                writeBytesQueue.pushLast(writeBuffer.array(), result, unwrittenBytes);
            }
            writeNextBlock(messages);
        });
    }

    @Override
    public void failed(Throwable exc, List<GeneratedMessage> messages) {
        writeExecutor.execute(
                () -> messages.forEach(
                        message -> messageWriteCompletionHandler.failed(exc, message)));
    }



}
