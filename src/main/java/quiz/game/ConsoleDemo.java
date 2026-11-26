package quiz.game;

import quiz.common.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner: Member 3.
 * Stand-alone console demo that runs a full 3-question game with no real network.
 *
 * <p>What it demonstrates:
 * <ul>
 *   <li>{@link QuestionLoader#load(Path)} — high-level NIO file API</li>
 *   <li>{@link QuestionLoader#loadWithChannel(Path)} — FileChannel + ByteBuffer</li>
 *   <li>Full LOBBY → QUESTION → REVEAL → FINISHED state machine</li>
 *   <li>Three player archetypes: fast (Alice), slow (Bob), late/no-answer (Charlie)</li>
 *   <li>A fake clock — tick() is called with hand-picked times so every transition
 *       is visible without waiting for real wall-clock seconds</li>
 * </ul>
 *
 * <p>Run from the project root with:
 * <pre>  mvn exec:java -Dexec.mainClass=quiz.game.ConsoleDemo</pre>
 * or:
 * <pre>  java -cp target/brainbrawl-1.0-SNAPSHOT.jar quiz.game.ConsoleDemo</pre>
 */
public class ConsoleDemo {

    // ── Entry point ───────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        printSep("BrainBrawl — ConsoleDemo (fake clock, no network)");

        // ── 1. Load questions (shows both NIO file APIs) ──────────────────
        Path qPath = Path.of("src/main/resources/questions.txt");
        List<Question> allQuestions = loadQuestionsForDemo(qPath);

        // Use the first 3 questions so the demo runs quickly
        List<Question> gameQuestions = allQuestions.subList(0, Math.min(3, allQuestions.size()));
        System.out.println("Using first " + gameQuestions.size() + " questions for the demo.\n");

        // ── 2. Set up engine with a recording listener ────────────────────
        RecordingListener listener = new RecordingListener();
        QuizGameEngine    engine   = new QuizGameEngine(gameQuestions, listener);

        // ── 3. Players join (also test the duplicate/invalid guards) ──────
        printSep("LOBBY");
        System.out.printf("  join(\"Alice\")  → %b%n", engine.join("Alice"));
        System.out.printf("  join(\"Bob\")    → %b%n", engine.join("Bob"));
        System.out.printf("  join(\"Charlie\")→ %b%n", engine.join("Charlie"));
        System.out.printf("  join(\"alice\")  → %b  (duplicate, case-insensitive)%n",
                          engine.join("alice"));
        System.out.printf("  join(\"bad|name\")→%b  (pipe char rejected)%n",
                          engine.join("bad|name"));
        System.out.printf("  join(\"\")       → %b  (blank rejected)%n",
                          engine.join(""));

        // ── 4. Start with a fake clock (t = 0 ms) ────────────────────────
        long t = 0L;
        engine.tick(t);     // sets lastTickMs so start() can compute deadlines
        engine.start();     // → broadcasts QUESTION for q1
        System.out.println();

        // ──────────────────────────────────────────────────────────────────
        // Questions 1–3: simulate fast / slow / late player behaviour
        // ──────────────────────────────────────────────────────────────────

        for (int round = 0; round < gameQuestions.size(); round++) {
            Question q       = gameQuestions.get(round);
            String   correct = indexToLetter(q.correctIndex());
            long     limit   = q.timeLimitSec() * 1_000L;

            printSep("ROUND " + (round + 1) + "  — " + q.id()
                     + "  (limit=" + q.timeLimitSec() + "s, correct=" + correct + ")");

            // Determine when this question's deadline is relative to t
            // (sendQuestion sets deadline = questionStartMs + limit;
            //  we know questionStartMs == the t value passed to the last
            //  tick() before sendQuestion was called.)
            long questionStartedAt = t;   // t was the value at start() / advanceQuestion()
            long ddl               = questionStartedAt + limit;

            if (round == 0) {
                // Alice answers fast (20% through time limit)
                t = questionStartedAt + (long)(limit * 0.20);
                engine.tick(t);
                engine.submitAnswer("Alice", q.id(), correct);  // correct + fast
                System.out.printf("  t=%5dms  Alice  submits %s (correct, fast)%n", t, correct);

                // Bob answers slow (70% through time limit)
                t = questionStartedAt + (long)(limit * 0.70);
                engine.tick(t);
                engine.submitAnswer("Bob", q.id(), correct);    // correct + slow
                System.out.printf("  t=%5dms  Bob    submits %s (correct, slow)%n", t, correct);

                // Charlie does not answer — deadline expires
                t = ddl + 1;
                engine.tick(t);   // → endRound (time-up)
                System.out.printf("  t=%5dms  DEADLINE passed — Charlie timed out%n", t);

            } else if (round == 1) {
                // Alice correct, Bob wrong, Charlie correct — all answer, round ends early
                t = questionStartedAt + 900L;
                engine.tick(t);
                engine.submitAnswer("Alice", q.id(), correct);
                System.out.printf("  t=%5dms  Alice  submits %s (correct)%n", t, correct);

                t = questionStartedAt + 1_900L;
                engine.tick(t);
                String wrongChoice = correct.equals("A") ? "B" : "A";
                engine.submitAnswer("Bob", q.id(), wrongChoice);  // wrong
                System.out.printf("  t=%5dms  Bob    submits %s (wrong)%n", t, wrongChoice);

                t = questionStartedAt + 2_900L;
                engine.tick(t);
                engine.submitAnswer("Charlie", q.id(), correct);
                System.out.printf("  t=%5dms  Charlie submits %s (correct)%n", t, correct);

                // All 3 answered → next tick ends round early
                t++;
                engine.tick(t);   // → endRound (all-answered, before deadline)
                System.out.printf("  t=%5dms  All answered — round ends early%n", t);

            } else {
                // Alice + Bob answer; Charlie submits AFTER the deadline (ignored)
                t = questionStartedAt + 900L;
                engine.tick(t);
                engine.submitAnswer("Alice", q.id(), correct);
                System.out.printf("  t=%5dms  Alice  submits %s (correct)%n", t, correct);

                t = questionStartedAt + 1_900L;
                engine.tick(t);
                engine.submitAnswer("Bob", q.id(), correct);
                System.out.printf("  t=%5dms  Bob    submits %s (correct)%n", t, correct);

                t = ddl + 1;
                engine.tick(t);   // → endRound (time-up, Charlie never answered)
                System.out.printf("  t=%5dms  DEADLINE — Charlie submits (too late, ignored):%n", t);
                engine.submitAnswer("Charlie", q.id(), correct);  // state=REVEAL → ignored
            }

            // Show state after endRound
            System.out.printf("  State: %s%n%n", engine.getState());

            // Advance through the 3-second reveal pause
            t += 3_100L;
            engine.tick(t);  // → advanceQuestion (or FINISHED)
            System.out.printf("  t=%5dms  Reveal ends — state: %s%n%n", t, engine.getState());

            // For the next iteration, questionStartedAt becomes the new t
            // (sendQuestion was called inside tick() above with this t value)
        }

        // ── 5. Final results ──────────────────────────────────────────────
        printSep("FINAL RESULTS");
        System.out.printf("  %-10s  %s%n", "Player", "Score");
        System.out.printf("  %-10s  %s%n", "------", "-----");
        for (var entry : engine.getRanking()) {
            System.out.printf("  %-10s  %d%n", entry.getKey(), entry.getValue());
        }
        System.out.println();
        System.out.println("Game state : " + engine.getState());
        System.out.println("Messages   : " + listener.messages.size() + " total");

        // ── 6. Full message log ───────────────────────────────────────────
        printSep("FULL MESSAGE LOG");
        for (Message m : listener.messages) {
            System.out.println("  " + format(m));
        }
    }

    // ── Helper: load questions, showing both NIO APIs ─────────────────────

    private static List<Question> loadQuestionsForDemo(Path qPath) {
        // Show the FileChannel + ByteBuffer method first (stronger NIO demo)
        try {
            List<Question> via = QuestionLoader.loadWithChannel(qPath);
            System.out.println("loadWithChannel() → " + via.size() + " questions loaded");
        } catch (IOException e) {
            System.out.println("loadWithChannel() failed: " + e.getMessage());
        }

        // Then use the Files.readAllLines() method as the actual source
        try {
            List<Question> loaded = QuestionLoader.load(qPath);
            System.out.println("load()            → " + loaded.size() + " questions loaded");
            return loaded;
        } catch (IOException e) {
            System.out.println("Could not read " + qPath + " (" + e.getMessage()
                               + "); using built-in fallback questions.");
            return fallbackQuestions();
        }
    }

    /** Fallback if the demo is run from a directory where questions.txt isn't visible. */
    private static List<Question> fallbackQuestions() {
        return List.of(
            new Question("q01", "What is the capital of France?",
                List.of("Berlin", "London", "Paris", "Rome"), 2, 12),
            new Question("q02", "How many sides does a hexagon have?",
                List.of("4", "5", "6", "8"), 2, 10),
            new Question("q03", "Which element has the chemical symbol Au?",
                List.of("Argon", "Arsenic", "Gold", "Aluminium"), 2, 12)
        );
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String indexToLetter(int i) {
        return String.valueOf((char) ('A' + i));
    }

    private static String format(Message m) {
        StringBuilder sb = new StringBuilder(m.getType());
        for (String f : m.getFields()) sb.append('|').append(f);
        return sb.toString();
    }

    private static void printSep(String title) {
        System.out.println("─".repeat(60));
        System.out.println("  " + title);
        System.out.println("─".repeat(60));
    }

    // ── Recording GameListener (also used in tests) ────────────────────────

    /**
     * Fake {@link GameListener}: prints every message and keeps a log.
     * Tests import and reuse this class via {@code ConsoleDemo.RecordingListener}.
     */
    static class RecordingListener implements GameListener {
        final List<Message> messages = new ArrayList<>();

        @Override
        public void broadcast(Message m) {
            messages.add(m);
            System.out.println("    [BROADCAST] " + format(m));
        }

        @Override
        public void sendTo(String player, Message m) {
            messages.add(m);
            System.out.println("    [→" + player + "]   " + format(m));
        }

        /** All messages of a given type, in the order they were sent. */
        List<Message> ofType(String type) {
            return messages.stream().filter(msg -> msg.getType().equals(type)).toList();
        }
    }
}
