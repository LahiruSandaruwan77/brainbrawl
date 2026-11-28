package quiz.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the per-client write queue, on a real loopback connection:
 * {@code session} wraps the server end (non-blocking, registered with a
 * Selector); {@code peer} is a plain blocking socket playing the client.
 */
@Timeout(20)
class ClientSessionTest {

    private ServerSocketChannel listener;
    private Selector selector;
    private Socket peer;
    private ClientSession session;
    private SelectionKey key;

    @BeforeEach
    void setUp() throws IOException {
        listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("localhost", 0));
        selector = Selector.open();

        // Small buffers on both ends so a "slow client" fills them quickly.
        peer = new Socket();
        peer.setReceiveBufferSize(4096);
        peer.connect(listener.getLocalAddress());
        peer.setSoTimeout(5_000);

        SocketChannel server = listener.accept();
        server.setOption(StandardSocketOptions.SO_SNDBUF, 4096);
        server.configureBlocking(false);
        session = new ClientSession(server);
        key = session.register(selector);
    }

    @AfterEach
    void tearDown() throws IOException {
        session.close();
        peer.close();
        selector.close();
        listener.close();
    }

    private boolean opWriteOn() {
        return (key.interestOps() & SelectionKey.OP_WRITE) != 0;
    }

    @Test
    void registerAttachesSessionForOpRead() {
        assertSame(session, key.attachment());
        assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void smallMessageIsSentAtOnceWithoutOpWrite() throws IOException {
        assertTrue(session.enqueue("WELCOME|alice\n"));

        assertFalse(session.hasPendingWrites());
        assertFalse(opWriteOn(), "OP_WRITE must be off when the queue is empty");
        assertEquals(0, session.getQueuedBytes());
        assertEquals("WELCOME|alice\n", readExactly(peer.getInputStream(), 14));
    }

    @Test
    void slowClientGetsOpWriteThenEverythingArrivesInOrder() throws Exception {
        // Fill the queue while the peer is NOT reading → partial write → OP_WRITE.
        String line = "X".repeat(999) + "\n";        // 1000 bytes
        StringBuilder expected = new StringBuilder();
        int n = 0;
        while (session.getQueuedBytes() + line.length() <= ClientSession.MAX_QUEUED_BYTES) {
            String msg = String.format("%05d", n++) + line.substring(5);
            assertTrue(session.enqueue(msg));
            expected.append(msg);
        }
        assertTrue(session.hasPendingWrites(), "socket should be full by now");
        assertTrue(opWriteOn(), "partial write must register OP_WRITE");

        // The client starts reading; the selector loop resumes the queue.
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                InputStream in = peer.getInputStream();
                byte[] buf = new byte[8192];
                while (received.size() < expected.length()) {
                    int r = in.read(buf);
                    if (r < 0) break;
                    synchronized (received) { received.write(buf, 0, r); }
                }
            } catch (IOException ignored) { }
        });
        reader.start();

        while (session.hasPendingWrites()) {
            selector.select(1_000);
            for (SelectionKey k : selector.selectedKeys()) {
                if (k.isValid() && k.isWritable()) ((ClientSession) k.attachment()).flush();
            }
            selector.selectedKeys().clear();
        }
        reader.join(5_000);

        assertFalse(opWriteOn(), "OP_WRITE must be removed once the queue drains");
        assertEquals(0, session.getQueuedBytes());
        assertEquals(expected.length(), session.getBytesSent());
        assertEquals(expected.toString(), received.toString(StandardCharsets.UTF_8),
                "bytes must arrive complete and in FIFO order");
    }

    @Test
    void stuckClientIsRefusedOnceQueueLimitIsReached() throws IOException {
        String line = "Y".repeat(1023) + "\n";
        int accepted = 0;
        // Peer never reads. Kernel buffers + MAX_QUEUED_BYTES is finite, so this must stop.
        while (session.enqueue(line)) {
            accepted++;
            assertTrue(accepted < 10_000, "queue limit never kicked in");
        }
        assertTrue(session.getQueuedBytes() <= ClientSession.MAX_QUEUED_BYTES);
        assertTrue(session.getQueuedBytes() + line.length() > ClientSession.MAX_QUEUED_BYTES);
    }

    @Test
    void closeIsIdempotentCancelsKeyAndRefusesNewMessages() throws IOException {
        assertTrue(session.close());
        assertFalse(session.close(), "second close is a no-op");
        assertFalse(session.isOpen());
        assertFalse(key.isValid(), "key must be cancelled");
        assertFalse(session.enqueue("SCORES|a:1\n"));
        assertEquals(-1, peer.getInputStream().read(), "peer sees EOF");
    }

    private static String readExactly(InputStream in, int n) throws IOException {
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }
}
