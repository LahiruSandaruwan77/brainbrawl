package quiz.server;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

/** Owner: Member 4. Per-client state: read buffer, write queue, name, score. */
public class ClientSession {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(4096);
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
    private String name;
    private int score;

    public ClientSession(SocketChannel channel) {
        this.channel = channel;
    }

    public SocketChannel getChannel() { return channel; }
    public ByteBuffer getReadBuffer() { return readBuffer; }
    public Queue<ByteBuffer> getWriteQueue() { return writeQueue; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getScore() { return score; }
    public void addScore(int points) { score += points; }

    // TODO: enqueue(Message), register OP_WRITE, flush queue, close on disconnect
}
