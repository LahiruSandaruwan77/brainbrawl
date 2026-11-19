package quiz.server;

import quiz.common.Protocol;

/** Owner: Member 1. Single-threaded NIO server using a Selector. */
public class QuizServer {

    public static void main(String[] args) throws Exception {
        System.out.println("BrainBrawl server starting on port " + Protocol.PORT);
        // TODO: open Selector + ServerSocketChannel (non-blocking)
        // TODO: register OP_ACCEPT, loop on selector.select()
        // TODO: handle isAcceptable / isReadable, hand off to ClientSession and GameEngine
    }
}
