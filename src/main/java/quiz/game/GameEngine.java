package quiz.game;

/** Owner: Member 3. Game state machine: LOBBY -> QUESTION -> RESULTS -> ... -> FINISHED. */
public class GameEngine {

    public enum State { LOBBY, QUESTION, RESULTS, FINISHED }

    private State state = State.LOBBY;

    public State getState() { return state; }

    // TODO: start(), nextQuestion(), submitAnswer(playerId, choice), tick() for the timer, scoring
}
