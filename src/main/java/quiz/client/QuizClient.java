package quiz.client;

import quiz.common.Protocol;

/** Owner: Member 5. NIO SocketChannel client; JavaFX GUI to be added. */
public class QuizClient {

    public static void main(String[] args) throws Exception {
        System.out.println("BrainBrawl client connecting to localhost:" + Protocol.PORT);
        // TODO: SocketChannel.open(new InetSocketAddress(host, Protocol.PORT))
        // TODO: reader thread for server messages, update JavaFX UI via Platform.runLater
    }
}
