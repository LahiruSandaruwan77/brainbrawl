package quiz.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class ClientConnection implements AutoCloseable {

    private volatile SocketChannel channel;
    private volatile boolean connected;
    private Thread readerThread;

    private final Consumer<String> messageHandler;
    private final Consumer<String> disconnectHandler;

    public ClientConnection(
            Consumer<String> messageHandler,
            Consumer<String> disconnectHandler) {

        this.messageHandler = messageHandler;
        this.disconnectHandler = disconnectHandler;
    }

    public void connect(String host, int port)
            throws IOException {

        if (connected) {
            throw new IOException("Already connected");
        }

        SocketChannel socket = SocketChannel.open();

        try {
            socket.configureBlocking(true);

            socket.socket().connect(
                    new InetSocketAddress(host, port),
                    5000
            );

            channel = socket;
            connected = true;

            startReader(socket);

        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void startReader(SocketChannel socket) {

        readerThread = new Thread(() -> {

            String reason = "Server disconnected";

            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.socket().getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );

                String message;

                while (connected &&
                        (message = reader.readLine()) != null) {

                    messageHandler.accept(message);
                }

            } catch (IOException e) {
                reason = "Connection lost: " + e.getMessage();

            } finally {

                boolean notify = connected;
                close();

                if (notify) {
                    disconnectHandler.accept(reason);
                }
            }

        }, "brainbrawl-network-reader");

        readerThread.setDaemon(true);
        readerThread.start();
    }

    public synchronized void send(String message)
            throws IOException {

        if (!isConnected()) {
            throw new IOException("Not connected to server");
        }

        if (message.contains("\n") || message.contains("\r")) {
            throw new IOException("Invalid message format");
        }

        ByteBuffer buffer = ByteBuffer.wrap(
                (message + "\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public boolean isConnected() {

        return connected &&
                channel != null &&
                channel.isOpen() &&
                channel.isConnected();
    }

    @Override
    public void close() {

        connected = false;

        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }
}