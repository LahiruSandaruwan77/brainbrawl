package quiz.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import quiz.common.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link QuizGameEngine}.
 *
 * <p>All tests use a {@link FakeListener} that records every broadcast/sendTo
 * call, and a hand-controlled clock — no threads, no real time.
 *
 * <p>Standard test setup (unless overridden): two questions, time limit 10 s each,
 * correct answer is always "B" (index 1).
 */
class QuizGameEngineTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Records every Message the engine sends (broadcast or sendTo). */
    static class FakeListener implements GameListener {
        final List<Message> messages = new ArrayList<>();

        @Override public void broadcast(Message m) { messages.add(m); }
        @Override public void sendTo(String p, Message m) { messages.add(m); }

        List<Message> ofType(String type) {
            return messages.stream().filter(m -> m.getType().equals(type)).toList();
        }
        void clear() { messages.clear(); }
    }

    /** Make a question with correct answer B (index 1) and given time limit. */
    private static Question q(String id, int timeLimitSec) {
        return new Question(id, "Question " + id,
            List.of("Wrong-A", "Correct-B", "Wrong-C", "Wrong-D"),
            1, timeLimitSec);
    }

    FakeListener   listener;
    QuizGameEngine engine;

    /** Default setup: two 10-second questions, correct answer "B". */
    @BeforeEach
    void setup() {
        listener = new FakeListener();
        engine   = new QuizGameEngine(List.of(q("q1", 10), q("q2", 10)), listener);
    }

    // ── join() ────────────────────────────────────────────────────────────

    @Test
    void duplicateUsernameRejected() {
        assertTrue(engine.join("Alice"));
        assertFalse(engine.join("Alice"),        "exact duplicate");
        assertFalse(engine.join("alice"),        "case-insensitive duplicate");
        assertFalse(engine.join("ALICE"),        "upper-case duplicate");
    }

    @Test
    void blankAndNullUsernameRejected() {
        assertFalse(engine.join(""));
        assertFalse(engine.join("   "));
        assertFalse(engine.join(null));
    }

    @Test
    void nameWithPipeOrNewlineRejected() {
        assertFalse(engine.join("ali|ce"),  "pipe char");
        assertFalse(engine.join("ali\nce"), "newline");
        assertFalse(engine.join("ali\rce"), "carriage return");
    }

    @Test
    void joinAfterStartRejected() {
        engine.join("Alice");
        engine.tick(0); engine.start();
        assertFalse(engine.join("Bob"), "join must be refused once game has started");
    }

    // ── start() ───────────────────────────────────────────────────────────

    @Test
    void startWithEmptyLobbyDoesNothing() {
        engine.tick(0);
        engine.start();
        assertEquals(QuizGameEngine.State.LOBBY, engine.getState());
        assertTrue(listener.messages.isEmpty(), "no messages should be sent");
    }

    @Test
    void startSendsFirstQuestion() {
        engine.join("Alice");
        engine.tick(0); engine.start();

        assertEquals(QuizGameEngine.State.QUESTION, engine.getState());
        List<Message> qs = listener.ofType("QUESTION");
        assertEquals(1, qs.size());
        assertEquals("q1", qs.get(0).getFields()[0], "first question id");
    }

    // ── submitAnswer() — late, duplicate, wrong-id ────────────────────────

    @Test
    void lateAnswerScoresZero() {
        engine.join("Alice");
        engine.tick(0); engine.start();
        // Advance past the 10-second deadline → endRound triggers in tick
        engine.tick(10_001);
        // Now in REVEAL; submitAnswer must be ignored
        engine.submitAnswer("Alice", "q1", "B");
        assertEquals(0, engine.getRanking().get(0).getValue(),
            "answer after deadline must score 0");
    }

    @Test
    void doubleAnswerOnlyCountsFirst() {
        engine.join("Alice");
        engine.tick(0); engine.start();

        engine.tick(2_000);
        engine.submitAnswer("Alice", "q1", "B");   // first: correct
        engine.tick(5_000);
        engine.submitAnswer("Alice", "q1", "A");   // second: wrong — must be ignored

        engine.tick(10_001); // endRound
        int score = engine.getRanking().get(0).getValue();
        assertTrue(score > 1000, "correct first answer should score > 1000, got " + score);
    }

    @Test
    void answerForWrongQuestionIdIgnored() {
        engine.join("Alice");
        engine.tick(0); engine.start();
        engine.tick(2_000);
        engine.submitAnswer("Alice", "q2", "B");  // current is q1
        engine.tick(10_001);  // endRound
        assertEquals(0, engine.getRanking().get(0).getValue());
    }

    @Test
    void wrongAnswerScoresZero() {
        engine.join("Alice");
        engine.tick(0); engine.start();
        engine.tick(2_000);
        engine.submitAnswer("Alice", "q1", "A");  // correct is B
        engine.tick(10_001);
        assertEquals(0, engine.getRanking().get(0).getValue());
    }

    // ── submitAnswer() — early termination ───────────────────────────────

    @Test
    void allPlayersAnswerEarlyEndsRoundBeforeDeadline() {
        engine.join("Alice");
        engine.join("Bob");
        engine.tick(0); engine.start();

        engine.tick(2_000);
        engine.submitAnswer("Alice", "q1", "B");
        engine.submitAnswer("Bob",   "q1", "B");
        // Both have answered — next tick should trigger endRound
        engine.tick(2_001);

        assertEquals(QuizGameEngine.State.REVEAL, engine.getState(),
            "both answered before deadline → should be in REVEAL");
        assertEquals(1, listener.ofType("ROUND_END").size());
    }

    // ── Scoring: speed bonus ──────────────────────────────────────────────

    @Test
    void fasterCorrectAnswerScoresMore() {
        engine.join("Alice");
        engine.join("Bob");
        engine.tick(0); engine.start();

        engine.tick(1_000);  engine.submitAnswer("Alice", "q1", "B");  // fast
        engine.tick(9_000);  engine.submitAnswer("Bob",   "q1", "B");  // slow
        engine.tick(10_001); // endRound

        var ranking = engine.getRanking();
        assertEquals("Alice", ranking.get(0).getKey(), "Alice should rank first");
        assertTrue(ranking.get(0).getValue() > ranking.get(1).getValue(),
            "faster answer should score more than slower");
    }

    // ── leave() ───────────────────────────────────────────────────────────

    @Test
    void playerLeavesMidRoundNoCrash() {
        engine.join("Alice");
        engine.join("Bob");
        engine.tick(0); engine.start();

        engine.tick(2_000);
        engine.submitAnswer("Alice", "q1", "B");
        engine.leave("Bob");   // Bob leaves; Alice is now the only player

        // Alice already answered and Bob is gone → allIn should trigger
        engine.tick(2_001);
        assertEquals(QuizGameEngine.State.REVEAL, engine.getState(),
            "game should continue after player leaves");
    }

    @Test
    void leaveUnknownPlayerDoesNotCrash() {
        engine.join("Alice");
        // Should not throw
        assertDoesNotThrow(() -> engine.leave("nobody"));
        assertDoesNotThrow(() -> engine.leave(null));
    }

    // ── Tie-breaking ─────────────────────────────────────────────────────

    @Test
    void tiebreakerFasterTotalAnswerTimeWins() {
        /*
         * Both players score 0 (wrong answers) so their scores are equal.
         * Alice answered first → lower total answer latency → ranks first.
         */
        engine.join("Alice");
        engine.join("Bob");
        engine.tick(0); engine.start();

        engine.tick(1_000); engine.submitAnswer("Alice", "q1", "A"); // wrong, fast
        engine.tick(9_000); engine.submitAnswer("Bob",   "q1", "A"); // wrong, slow
        engine.tick(10_001);

        var ranking = engine.getRanking();
        assertEquals(0, ranking.get(0).getValue(), "both should score 0");
        assertEquals("Alice", ranking.get(0).getKey(),
            "Alice answered faster → should rank first on tie");
    }

    @Test
    void tiebreakerAlphabeticalWhenScoreAndTimeIdentical() {
        /*
         * Both players answer at the same tick → identical score and
         * identical latency.  Alphabetical order must decide.
         */
        var l2 = new FakeListener();
        var e2 = new QuizGameEngine(List.of(q("q1", 10)), l2);
        e2.join("Zara");
        e2.join("Ann");
        e2.tick(0); e2.start();

        e2.tick(5_000);
        e2.submitAnswer("Zara", "q1", "B");
        e2.submitAnswer("Ann",  "q1", "B");
        e2.tick(10_001); // endRound

        var ranking = e2.getRanking();
        assertEquals("Ann",  ranking.get(0).getKey(), "Ann < Zara alphabetically");
        assertEquals("Zara", ranking.get(1).getKey());
    }

    // ── Single-player ─────────────────────────────────────────────────────

    @Test
    void singlePlayerGameEndsWithWinner() {
        var l = new FakeListener();
        var e = new QuizGameEngine(List.of(q("q1", 10)), l);
        e.join("Solo");
        e.tick(0); e.start();

        e.tick(3_000); e.submitAnswer("Solo", "q1", "B");
        e.tick(3_001); // all answered → endRound
        e.tick(6_200); // reveal ends → FINISHED

        assertEquals(QuizGameEngine.State.FINISHED, e.getState());
        List<Message> finals = l.ofType("FINAL");
        assertEquals(1, finals.size(), "exactly one FINAL message");
        String[] fields = finals.get(0).getFields();
        assertEquals("WINNER", fields[fields.length - 2]);
        assertEquals("Solo",   fields[fields.length - 1]);
    }

    // ── QuestionLoader ────────────────────────────────────────────────────

    @Test
    void malformedLineGivesClearErrorWithLineNumber() throws IOException {
        Path tmp = Files.createTempFile("bad", ".txt");
        // Line 1: valid;  Line 2: only 5 fields (bad)
        Files.writeString(tmp, "id1|text|A|B|C|D|1|10\nbad|only|five|fields|here\n");
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> QuestionLoader.load(tmp));
            String msg = ex.getMessage();
            assertTrue(msg.contains("2"),
                "Error message should reference line 2, got: " + msg);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void correctIndexOutOfRangeGivesClearError() throws IOException {
        Path tmp = Files.createTempFile("bad2", ".txt");
        Files.writeString(tmp, "id1|text|A|B|C|D|5|10\n");  // correctIndex=5 is invalid
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> QuestionLoader.load(tmp));
            assertTrue(ex.getMessage().contains("1"),
                "Error should reference line 1");
            assertTrue(ex.getMessage().toLowerCase().contains("correctindex"),
                "Error should name the bad field");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void commentsAndBlankLinesAreSkipped() throws IOException {
        Path tmp = Files.createTempFile("clean", ".txt");
        Files.writeString(tmp, """
            # This is a comment

            id1|Question text|A|B|C|D|0|10
            # Another comment
            """);
        try {
            List<Question> qs = QuestionLoader.load(tmp);
            assertEquals(1, qs.size());
            assertEquals("id1", qs.get(0).id());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void loadWithChannelProducesSameResultAsLoad() throws IOException {
        Path tmp = Files.createTempFile("chan", ".txt");
        Files.writeString(tmp, "qa|Alpha?|Yes|No|Maybe|Never|0|12\n"
                             + "qb|Beta?|X|Y|Z|W|2|15\n");
        try {
            List<Question> viaFiles   = QuestionLoader.load(tmp);
            List<Question> viaChan    = QuestionLoader.loadWithChannel(tmp);
            assertEquals(viaFiles.size(), viaChan.size());
            for (int i = 0; i < viaFiles.size(); i++) {
                assertEquals(viaFiles.get(i).id(),           viaChan.get(i).id());
                assertEquals(viaFiles.get(i).correctIndex(), viaChan.get(i).correctIndex());
                assertEquals(viaFiles.get(i).timeLimitSec(), viaChan.get(i).timeLimitSec());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Full game message-order integration test ──────────────────────────

    @Test
    void fullGameProducesMessagesInCorrectOrder() {
        engine.join("Alice");
        engine.join("Bob");
        engine.tick(0); engine.start();

        // --- Q1 ---
        engine.tick(2_000); engine.submitAnswer("Alice", "q1", "B");
        engine.tick(5_000); engine.submitAnswer("Bob",   "q1", "A");  // wrong
        engine.tick(10_001);  // endRound  → ROUND_END + LEADERBOARD
        engine.tick(13_100);  // reveal    → Q2

        // --- Q2 ---
        engine.tick(14_000); engine.submitAnswer("Alice", "q2", "B");
        engine.tick(15_000); engine.submitAnswer("Bob",   "q2", "B");
        engine.tick(15_001);  // all answered → endRound
        engine.tick(18_200);  // reveal → FINISHED

        List<String> types = listener.messages.stream()
            .map(Message::getType).toList();

        // Expected sequence:
        // QUESTION(q1) → ROUND_END → LEADERBOARD → QUESTION(q2) → ROUND_END → LEADERBOARD → FINAL
        int q1Idx  = types.indexOf("QUESTION");
        int r1Idx  = types.indexOf("ROUND_END");
        int lb1Idx = types.indexOf("LEADERBOARD");
        int q2Idx  = types.lastIndexOf("QUESTION");
        int r2Idx  = types.lastIndexOf("ROUND_END");
        int lb2Idx = types.lastIndexOf("LEADERBOARD");
        int fiIdx  = types.indexOf("FINAL");

        // Verify strict ordering
        assertTrue(q1Idx  >= 0,         "must have at least one QUESTION");
        assertTrue(r1Idx  >  q1Idx,     "ROUND_END must follow QUESTION");
        assertTrue(lb1Idx >  r1Idx,     "LEADERBOARD must follow ROUND_END");
        assertTrue(q2Idx  >  lb1Idx,    "second QUESTION must follow first LEADERBOARD");
        assertTrue(r2Idx  >  q2Idx,     "second ROUND_END must follow second QUESTION");
        assertTrue(lb2Idx >  r2Idx,     "second LEADERBOARD must follow second ROUND_END");
        assertTrue(fiIdx  >  lb2Idx,    "FINAL must come after last LEADERBOARD");

        // Verify no extra QUESTION or FINAL messages
        assertEquals(2, listener.ofType("QUESTION").size());
        assertEquals(1, listener.ofType("FINAL").size());

        // Verify winner is Alice (scored more by answering correctly)
        String[] finalFields = listener.ofType("FINAL").get(0).getFields();
        assertEquals("WINNER", finalFields[finalFields.length - 2]);
        assertEquals("Alice",  finalFields[finalFields.length - 1]);
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    void tickInLobbyOrFinishedDoesNothing() {
        // LOBBY
        engine.join("Alice");
        engine.tick(999_999);
        assertEquals(QuizGameEngine.State.LOBBY, engine.getState());
        assertTrue(listener.messages.isEmpty());

        // Run to FINISHED
        engine.tick(0); engine.start();
        engine.tick(10_001); engine.tick(13_200); // Q1 done
        engine.tick(23_201); engine.tick(26_400); // Q2 done → FINISHED
        listener.clear();

        // Tick in FINISHED — must be a no-op
        engine.tick(999_999);
        assertTrue(listener.messages.isEmpty(),
            "tick() in FINISHED state should send no messages");
    }

    @Test
    void getRankingReturnsDefensiveCopy() {
        engine.join("Alice");
        engine.tick(0); engine.start();
        engine.tick(10_001); // endRound
        var r1 = engine.getRanking();
        var r2 = engine.getRanking();
        assertNotSame(r1, r2, "getRanking must return a new list each time");
    }
}
