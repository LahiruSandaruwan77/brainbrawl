package quiz.game;

import java.util.List;
import java.util.Map;

/**
 * Owner: Member 3.
 * Public API of the game engine. The server's Selector loop drives the engine
 * by calling {@link #tick(long)} on every wake-up and routing decoded messages
 * to {@link #submitAnswer(String, String, String)}.
 *
 * State machine: LOBBY → QUESTION → REVEAL → (next QUESTION …) → FINISHED.
 */
public interface GameEngine {

    /**
     * Add a player to the lobby.
     *
     * @return true on success; false when the name is null/blank, contains '|'
     *         or newline characters, is a case-insensitive duplicate, or when
     *         the game is no longer in LOBBY state.
     */
    boolean join(String username);

    /**
     * Remove a player. Safe to call in any state; never throws for unknown names.
     */
    void leave(String username);

    /**
     * Transition from LOBBY to the first QUESTION.
     * Does nothing if the game is not in LOBBY state or if no players have joined.
     */
    void start();

    /**
     * Record a player's answer. Silently ignored when:
     * <ul>
     *   <li>state is not QUESTION</li>
     *   <li>the player is unknown</li>
     *   <li>qId does not match the current question</li>
     *   <li>the deadline has already passed</li>
     *   <li>the player has already answered this round</li>
     * </ul>
     *
     * @param choice one of "A", "B", "C", "D"
     */
    void submitAnswer(String username, String qId, String choice);

    /**
     * Advance the game clock. Called by the Selector loop on every wake-up.
     * <ul>
     *   <li>QUESTION: ends the round when {@code nowMillis >= deadline} or all
     *       current players have answered.</li>
     *   <li>REVEAL: advances to the next question (or FINISHED) after 3 seconds.</li>
     *   <li>LOBBY / FINISHED: no-op.</li>
     * </ul>
     *
     * @param nowMillis injected wall-clock time — never call
     *                  {@code System.currentTimeMillis()} inside the engine
     */
    void tick(long nowMillis);

    /**
     * Returns a snapshot of scores, sorted descending by score, then by lower
     * total answer latency, then alphabetically by username.
     */
    List<Map.Entry<String, Integer>> getRanking();
}
