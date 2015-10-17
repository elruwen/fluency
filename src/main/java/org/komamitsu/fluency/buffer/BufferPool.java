package org.komamitsu.fluency.buffer;

import org.msgpack.core.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BufferPool
{
    @VisibleForTesting
    final Map<Integer, LinkedBlockingQueue<ByteBuffer>> bufferPool = new HashMap<Integer, LinkedBlockingQueue<ByteBuffer>>();
    private final AtomicLong allocatedSize = new AtomicLong();
    private final int initialBufferSize;
    private final int maxBufferSize;

    public BufferPool(int initialBufferSize, int maxBufferSize)
    {
        this.initialBufferSize = initialBufferSize;
        this.maxBufferSize = maxBufferSize;
    }

    public ByteBuffer acquireBuffer(int bufferSize)
    {
        int normalizedBufferSize = initialBufferSize;
        while (normalizedBufferSize < bufferSize) {
            normalizedBufferSize *= 2;
        }

        LinkedBlockingQueue<ByteBuffer> buffers;
        synchronized (bufferPool) {
            buffers = bufferPool.get(normalizedBufferSize);
            if (buffers == null) {
                buffers = new LinkedBlockingQueue<ByteBuffer>();
                bufferPool.put(normalizedBufferSize, buffers);
            }
        }

        ByteBuffer buffer = buffers.poll();
        if (buffer != null) {
            return buffer;
        }

        /*
        synchronized (allocatedSize) {
            if (allocatedSize.get() + normalizedBufferSize > maxBufferSize) {
                return null;    // `null` means the buffer is full.
            }
            allocatedSize.addAndGet(normalizedBufferSize);
            return ByteBuffer.allocateDirect(normalizedBufferSize);
        }
        */

        while (true) {
            long currentAllocatedSize = allocatedSize.get();
            if (currentAllocatedSize + normalizedBufferSize > maxBufferSize) {
                releaseBuffers();
                return null;    // `null` means the buffer is full.
            }
            if (currentAllocatedSize == allocatedSize.getAndAdd(normalizedBufferSize)) {
                return ByteBuffer.allocateDirect(normalizedBufferSize);
            }
            allocatedSize.getAndAdd(-normalizedBufferSize);
        }
    }

    public void returnBuffer(ByteBuffer byteBuffer)
    {
        LinkedBlockingQueue<ByteBuffer> buffers = bufferPool.get(byteBuffer.capacity());
        if (buffers == null) {
            throw new IllegalStateException("`buffers` shouldn't be null");
        }

        byteBuffer.position(0);
        byteBuffer.limit(byteBuffer.capacity());
        buffers.offer(byteBuffer);
    }

    public long getAllocatedSize()
    {
        return allocatedSize.get();
    }

    public void releaseBuffers()
    {
        synchronized (bufferPool) {
            for (Map.Entry<Integer, LinkedBlockingQueue<ByteBuffer>> entry : bufferPool.entrySet()) {
                ByteBuffer buffer;
                while ((buffer = entry.getValue().poll()) != null) {
                    allocatedSize.addAndGet(-buffer.capacity());
                }
            }
        }
    }

    @Override
    public String toString()
    {
        return "BufferPool{" +
                "bufferPool=" + bufferPool +
                ", allocatedSize=" + allocatedSize +
                ", initialBufferSize=" + initialBufferSize +
                ", maxBufferSize=" + maxBufferSize +
                '}';
    }
}
