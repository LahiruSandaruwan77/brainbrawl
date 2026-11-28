package quiz.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Owner: Member 4.
 *
 * <h2>One connected client: its channel, read buffer and write queue</h2>
 *
 * <p>The server never calls {@code channel.write()} directly. Every outgoing
 * line goes through {@link #enqueue(String)}, which puts it on this client's
 * own write queue and immediately tries to {@link #flush()} it.
 *
 * <pre>
 *   enqueue("QUESTION|...\n")
 *        │
 *        ▼
 *   writeQueue: [buf1][buf2][buf3]      ← one ByteBuffer per message, FIFO
 *        │
 *   flush(): write head buffer
 *        ├── fully written  → poll(), try the next one
 *        └── partly written → socket send buffer is full (slow client):
 *                             turn ON OP_WRITE and return. The Selector wakes
 *                             us when the socket has room; flush() resumes.
 *   queue empty → turn OFF OP_WRITE (a socket is almost always writable, so
 *                 leaving it on would make select() spin at 100% CPU).
 * </pre>
 *
 * <p><b>Slow-client protection.</b> A client that stops reading would make its
 * queue grow forever. Once more than {@link #MAX_QUEUED_BYTES} are waiting,
 * {@link #enqueue(String)} refuses the message and returns false; the server
 * then disconnects that client so one stuck player cannot exhaust memory or
 * hold up everyone else.
 *
 * <p>All methods run on the single selector thread, so no locking is needed.
 */
public class ClientSession {

    /** Size of the per-client read buffer; a line longer than this is rejected. */
    public static final int READ_BUFFER_SIZE = 4096;

    /** Max bytes allowed to wait in the write queue before the client counts as stuck. */
    public static final int MAX_QUEUED_BYTES = 64 * 1024;

    private final SocketChannel channel;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();

    /** Bytes still unsent across the whole queue (kept in step with writeQueue). */
    private int queuedBytes;

    /** This client's registration with the Selector; null until {@link #register}. */
    private SelectionKey key;

    /** Remote address, captured at creation so logs still work after close. */
    private final String remote;

    /** Username after a successful JOIN, null before. */
    private String name;

    /** Total bytes handed to the socket, for the status/log output. */
    private long bytesSent;

    public ClientSession(SocketChannel channel) {
        this.channel = channel;
        this.remote = describe(channel);
    }

    // ── Selector registration ─────────────────────────────────────────────

    /**
     * Register for OP_READ and attach this session to the key, so the
     * selector loop can find the session from any ready key.
     */
    public SelectionKey register(Selector selector) throws ClosedChannelException {
        key = channel.register(selector, SelectionKey.OP_READ, this);
        return key;
    }

    // ── Writing (OP_WRITE) ────────────────────────────────────────────────

    /**
     * Queue one already-framed line (it must end in '\n') and try to send it.
     *
     * @return false if the client is closed or too slow (queue over
     *         {@link #MAX_QUEUED_BYTES}). The caller should then disconnect it.
     * @throws IOException if the socket write fails (peer reset, etc.)
     */
    public boolean enqueue(String framedLine) throws IOException {
        if (!isOpen()) return false;
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(framedLine);
        if (queuedBytes + bytes.remaining() > MAX_QUEUED_BYTES) {
            return false;                        // stuck client: refuse, caller drops it
        }
        writeQueue.add(bytes);
        queuedBytes += bytes.remaining();
        flush();
        return true;
    }

    /**
     * Write as much of the queue as the socket accepts right now. Called by
     * {@link #enqueue} and by the selector loop when the key is writable.
     */
    public void flush() throws IOException {
        if (key == null || !key.isValid()) return;

        while (!writeQueue.isEmpty()) {
            ByteBuffer head = writeQueue.peek();
            int written = channel.write(head);   // non-blocking: may write 0..all bytes
            queuedBytes -= written;
            bytesSent += written;
            if (head.hasRemaining()) {
                // Socket full: wake me when there's room again.
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                return;
            }
            writeQueue.poll();                   // this message is fully sent
        }
        // Nothing left: stop asking for OP_WRITE, or select() would spin.
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    /** True while bytes are waiting to be sent. */
    public boolean hasPendingWrites() { return !writeQueue.isEmpty(); }

    /** Bytes currently waiting in the write queue. */
    public int getQueuedBytes() { return queuedBytes; }

    /** Messages currently waiting in the write queue. */
    public int getQueuedMessages() { return writeQueue.size(); }

    /** Total bytes handed to the socket so far. */
    public long getBytesSent() { return bytesSent; }

    // ── Closing ───────────────────────────────────────────────────────────

    /**
     * Cancel the key, close the socket and drop queued messages. Safe to call
     * more than once.
     *
     * @return true if this call closed it, false if it was already closed
     */
    public boolean close() {
        if (!channel.isOpen()) return false;
        if (key != null) key.cancel();           // Selector stops watching this channel
        try { channel.close(); } catch (IOException ignored) { }
        writeQueue.clear();
        queuedBytes = 0;
        return true;
    }

    public boolean isOpen() { return channel.isOpen(); }

    // ── Accessors ─────────────────────────────────────────────────────────

    public SocketChannel getChannel() { return channel; }
    public ByteBuffer getReadBuffer() { return readBuffer; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isJoined() { return name != null; }
    public String getRemoteAddress() { return remote; }

    /** "alice@/127.0.0.1:53122" or just the address before JOIN. */
    @Override
    public String toString() {
        return (name != null ? name + "@" : "") + remote;
    }

    private static String describe(SocketChannel ch) {
        try {
            SocketAddress a = ch.getRemoteAddress();
            return a != null ? a.toString() : "?";
        } catch (IOException e) {
            return "?";
        }
    }
}
