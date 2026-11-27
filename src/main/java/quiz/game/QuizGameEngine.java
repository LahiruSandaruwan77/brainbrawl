package quiz.game;

import quiz.common.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner: Member 3.
 * Single-threaded game state machine for BrainBrawl.
 *
 * <pre>
 *   LOBBY ──start()──► QUESTION ──endRound()──► REVEAL
 *                           ▲                       │
 *                           └──advanceQuestion()────┘
 *                                   │ (no more questions)
 *                                   ▼
 *                               FINISHED
 * </pre>
 *
 * <p>All time values are injected via {@link #tick(long)}; this class never
 * calls {@code System.currentTimeMillis()}, making every test deterministic.
 *
 * <p>The server's Selector loop drives the engine:
 * <ol>
 *   <li>Call {@code tick(now)} on every selector wake-up.</li>
 *   <li>Route decoded ANSWER messages to {@code submitAnswer(...)}.</li>
 *   <li>{@code submitAnswer} uses the time recorded by the most recent
 *       {@code tick()} call, so answers are timestamped to the nearest
 *       selector interval — accurate enough for fair scoring.</li>
 * </ol>
 */
public class QuizGameEngine implements GameEngine {

    // ── State enum (package-visible so ConsoleDemo can read it) ──────────
    public enum State { LOBBY, QUESTION, REVEAL, FINISHED }

    /** Duration of the reveal (show-correct-answer) pause between questions. */
    private static final long REVEAL_MS = 3_000L;

    // ── Injected dependencies ─────────────────────────────────────────────
    private final List<Question> questions;
    private final GameListener   listener;

    // ── Persistent game state ─────────────────────────────────────────────
    private State state = State.LOBBY;

    /**
     * Insertion-ordered map: username (original case) → cumulative score.
     * Insertion order is preserved for deterministic iteration in tests.
     */
    private final LinkedHashMap<String, Integer> scores = new LinkedHashMap<>();

    /**
     * Sum of answer-latency milliseconds per player across all rounds.
     * Lower is better for tie-breaking when scores are equal.
     */
    private final Map<String, Long> totalAnswerMs = new LinkedHashMap<>();

    // ── Per-question state ────────────────────────────────────────────────
    private int      questionIndex = -1;
    private Question current;                       // convenience alias into questions

    /** This round's answers: username → AnswerRecord (first answer wins). */
    private final Map<String, AnswerRecord> roundAnswers = new HashMap<>();

    private long questionStartMs;   // when the current question was broadcast
    private long deadline;          // questionStartMs + timeLimitSec * 1000
    private long revealUntil;       // when REVEAL transitions to the next question

    /**
     * Most recent time seen by {@link #tick(long)}.
     * Also used by {@link #start()} to set the first question's deadline,
     * so callers should tick(initialTime) before start() for precise timing.
     */
    private long lastTickMs = 0L;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param questions list of questions to play (order is preserved)
     * @param listener  network callback — the engine never touches sockets directly
     */
    public QuizGameEngine(List<Question> questions, GameListener listener) {
        this.questions = List.copyOf(questions);   // defensive copy
        this.listener  = listener;
    }

    // ── GameEngine API ────────────────────────────────────────────────────

    @Override
    public boolean join(String username) {
        if (state != State.LOBBY) return false;
        if (username == null || username.isBlank()) return false;
        // Reject names that would corrupt the pipe-delimited protocol
        if (username.contains("|") || username.contains("\n") || username.contains("\r")) return false;
        // Case-insensitive duplicate check
        for (String existing : scores.keySet()) {
            if (existing.equalsIgnoreCase(username)) return false;
        }
        scores.put(username, 0);
        totalAnswerMs.put(username, 0L);
        return true;
    }

    @Override
    public void leave(String username) {
        // Safe in any state; HashMap/LinkedHashMap.remove() is a no-op for unknown keys.
        scores.remove(username);
        totalAnswerMs.remove(username);
        roundAnswers.remove(username);
    }

    @Override
    public void start() {
        if (state != State.LOBBY) return;
        if (scores.isEmpty())     return;

        if (questions.isEmpty()) {
            // Edge case: someone loaded an empty question file.
            state = State.FINISHED;
            broadcastFinal();
            return;
        }

        questionIndex = 0;
        sendQuestion(lastTickMs);   // uses time from most recent tick()
    }

    /**
     * Record a player's answer. Silently ignores anything invalid so the
     * game is robust to replayed, duplicate, or stray network messages.
     */
    @Override
    public void submitAnswer(String username, String qId, String choice) {
        if (state != State.QUESTION)            return;
        if (!scores.containsKey(username))      return;   // unknown player
        if (!current.id().equals(qId))          return;   // wrong question
        if (lastTickMs >= deadline)             return;   // past deadline
        if (roundAnswers.containsKey(username)) return;   // duplicate answer

        // Record with the timestamp of the most recent tick (single-threaded model).
        roundAnswers.put(username, new AnswerRecord(choice, lastTickMs));
    }

    /**
     * Advance the game clock. Called by the server's Selector loop on every
     * wake-up. This is where timer-based transitions happen.
     */
    @Override
    public void tick(long nowMillis) {
        lastTickMs = nowMillis;

        if (state == State.QUESTION) {
            boolean timeUp = nowMillis >= deadline;
            // "all answered" is only possible when at least one player remains
            boolean allIn  = !scores.isEmpty()
                             && roundAnswers.size() == scores.size();
            if (timeUp || allIn) {
                endRound();
            }

        } else if (state == State.REVEAL) {
            if (nowMillis >= revealUntil) {
                advanceQuestion(nowMillis);
            }
        }
        // LOBBY / FINISHED: no-op
    }

    @Override
    public List<Map.Entry<String, Integer>> getRanking() {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(scores.entrySet());
        list.sort((a, b) -> {
            // 1. Higher score first
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            // 2. Lower total answer latency first (faster player wins tie)
            long tA = totalAnswerMs.getOrDefault(a.getKey(), Long.MAX_VALUE);
            long tB = totalAnswerMs.getOrDefault(b.getKey(), Long.MAX_VALUE);
            cmp = Long.compare(tA, tB);
            if (cmp != 0) return cmp;
            // 3. Alphabetical as final tie-breaker
            return a.getKey().compareTo(b.getKey());
        });
        return Collections.unmodifiableList(list);
    }

    // ── Package-visible accessor for ConsoleDemo / tests ─────────────────
    public State getState() { return state; }

    // ── Private state-transition helpers ─────────────────────────────────

    /**
     * Broadcast the question at index {@code questionIndex} and start the timer.
     *
     * <p>Protocol message: {@code QUESTION|id|text|A|B|C|D|timeLimitSec}
     */
    private void sendQuestion(long nowMillis) {
        state            = State.QUESTION;
        current          = questions.get(questionIndex);
        questionStartMs  = nowMillis;
        deadline         = nowMillis + current.timeLimitSec() * 1_000L;
        roundAnswers.clear();

        listener.broadcast(new Message(
            "QUESTION",
            current.id(),
            current.text(),
            current.options().get(0),
            current.options().get(1),
            current.options().get(2),
            current.options().get(3),
            String.valueOf(current.timeLimitSec())
        ));
    }

    /**
     * Score the round, broadcast results, and enter the REVEAL pause.
     *
     * <p>Scoring: correct answer = 1000 + ⌊500 × remainingMs / limitMs⌋ (bonus for speed).
     * Wrong answer or no answer = 0 points.
     */
    private void endRound() {
        String correctLetter = indexToLetter(current.correctIndex());
        long   limitMs       = current.timeLimitSec() * 1_000L;

        // Award points to every player still in the game
        for (String player : new ArrayList<>(scores.keySet())) {
            AnswerRecord rec = roundAnswers.get(player);
            if (rec == null) continue;   // no answer → 0 points, skip

            // Accumulate answer latency for tie-breaking (even for wrong answers)
            long latency = rec.timeMs() - questionStartMs;
            totalAnswerMs.merge(player, latency, Long::sum);

            if (correctLetter.equals(rec.choice())) {
                // remainingMs clamped to [0, limitMs] for safety
                long remainingMs = Math.max(0L, Math.min(limitMs, deadline - rec.timeMs()));
                int  pts         = 1000 + (int) (500L * remainingMs / limitMs);
                scores.merge(player, pts, Integer::sum);
            }
            // wrong answer: latency still recorded, 0 points added
        }

        // Broadcast round outcome — RESULT|questionId|correctLetter
        listener.broadcast(new Message("RESULT", current.id(), correctLetter));

        // Broadcast scores as a single comma-joined field — SCORES|alice:1850,bob:900,...
        String scoreField = getRanking().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        listener.broadcast(new Message("SCORES", scoreField));

        state       = State.REVEAL;
        revealUntil = lastTickMs + REVEAL_MS;
    }

    /**
     * Move to the next question, or end the game if none remain.
     */
    private void advanceQuestion(long nowMillis) {
        questionIndex++;
        if (questionIndex < questions.size()) {
            sendQuestion(nowMillis);
        } else {
            state = State.FINISHED;
            broadcastFinal();
        }
    }

    /**
     * Broadcast the final standings.
     *
     * <p>Protocol message: {@code END|winnerName}
     */
    private void broadcastFinal() {
        List<Map.Entry<String, Integer>> ranking = getRanking();
        String winner = ranking.isEmpty() ? "none" : ranking.get(0).getKey();
        listener.broadcast(new Message("END", winner));
    }

    private static String indexToLetter(int i) {
        return String.valueOf((char) ('A' + i));   // 0→"A", 1→"B", 2→"C", 3→"D"
    }

    // ── Inner record ──────────────────────────────────────────────────────

    /** Immutable snapshot of one player's answer this round. */
    private record AnswerRecord(String choice, long timeMs) {}
}
