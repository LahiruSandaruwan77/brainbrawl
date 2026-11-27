package quiz.game;

import quiz.common.Message;

/**
 * Owner: Member 3.
 * Callback interface so the engine can send messages without touching sockets.
 * Implemented by the server layer (Member 1 / Member 4).
 */
public interface GameListener {

    /** Send a message to every connected player. */
    void broadcast(Message m);

    /** Send a message to one specific player (by username). */
    void sendTo(String player, Message m);
}
