package quiz.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quiz.game.Question;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests: a real QuizServer (real QuizGameEngine) on a random free
 * port, driven by plain blocking sockets. Every socket read has a timeout and
 * every test has an overall timeout, so nothing can hang the build.
 */
@Timeout(20)
class QuizServerTest {

    /** One short question; correct answer is B. 2 s limit keeps tests fast. */
    private static final List<Question> QUESTIONS = List.of(
            new Question("q1", "2 + 2 = ?", List.of("3", "4", "5", "6"), 1, 2));

    /** Long lobby wait so the game only starts when a test asks for it. */
    private static final long NO_AUTO_START = 60_000L;

    private QuizServer server;
    private Thread serverThread;
    private final List<TestClient> clients = new ArrayList<>();

    private void startServer(long lobbyWaitMs) throws IOException {
        server = new QuizServer(0, QUESTIONS, lobbyWaitMs);   // port 0 = random free port
        serverThread = new Thread(server, "quiz-server-under-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private TestClient connect() throws IOException {
        TestClient c = new TestClient(server.getPort());
        clients.add(c);
        return c;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (TestClient c : clients) c.close();
        if (server != null) server.close();
        if (serverThread != null) {
            serverThread.join(5_000);
            assertFalse(serverThread.isAlive(), "server thread should stop on close()");
        }
    }

    // ── 1 ────────────────────────────────────────────────────────────────

    @Test
    void joinGetsWelcomeAndDuplicateNameGetsError() throws Exception {
        startServer(NO_AUTO_START);
        TestClient alice = connect();
        alice.send("JOIN|alice");
        assertEquals("WELCOME|alice", alice.readLine());

        TestClient copycat = connect();
        copycat.send("JOIN|Alice");                  // case-insensitive duplicate
        assertTrue(copycat.readLine().startsWith("ERROR|"), "duplicate name must be rejected");

        // The rejected client can still join under another name.
        copycat.send("JOIN|bob");
        assertEquals("WELCOME|bob", copycat.readLine());
    }

    // ── 2 ────────────────────────────────────────────────────────────────

    @Test
    void lineSplitAcrossTwoWritesIsOneLine() throws Exception {
        startServer(NO_AUTO_START);
        TestClient c = connect();
        c.sendRaw("JOI");
        Thread.sleep(150);                           // make the server see a partial line first
        c.sendRaw("N|carol\n");
        assertEquals("WELCOME|carol", c.readLine());
    }

    // ── 3 ────────────────────────────────────────────────────────────────

    @Test
    void twoLinesInOneWriteAreBothProcessed() throws Exception {
        startServer(NO_AUTO_START);
        TestClient c = connect();
        c.sendRaw("JOIN|dave\nHELLO\n");             // one TCP write, two lines
        assertEquals("WELCOME|dave", c.readLine());
        assertTrue(c.readLine().startsWith("ERROR|"), "second line (unknown) must get ERROR");
    }

    // ── 4 ────────────────────────────────────────────────────────────────

    @Test
    void disconnectedClientIsRemovedAndOthersKeepWorking() throws Exception {
        startServer(NO_AUTO_START);
        TestClient alice = connect();
        TestClient bob = connect();
        alice.send("JOIN|alice");
        assertEquals("WELCOME|alice", alice.readLine());
        bob.send("JOIN|bob");
        assertEquals("WELCOME|bob", bob.readLine());

        alice.close();

        // engine.leave("alice") ran ⇔ the name "alice" is free again.
        // Retry briefly: the server notices the close asynchronously.
        TestClient alice2 = connect();
        String reply = null;
        for (int i = 0; i < 20; i++) {
            alice2.send("JOIN|alice");
            reply = alice2.readLine();
            if (reply.startsWith("WELCOME")) break;
            Thread.sleep(50);
        }
        assertEquals("WELCOME|alice", reply);

        // Bob's connection is still served.
        bob.send("NONSENSE");
        assertTrue(bob.readLine().startsWith("ERROR|"));
    }

    // ── 5 ────────────────────────────────────────────────────────────────

    @Test
    void fullGameQuestionResultScoresEnd() throws Exception {
        startServer(NO_AUTO_START);
        TestClient alice = connect();
        TestClient bob = connect();
        alice.send("JOIN|alice");
        assertEquals("WELCOME|alice", alice.readLine());
        bob.send("JOIN|bob");
        assertEquals("WELCOME|bob", bob.readLine());

        server.submitConsoleCommand("start");        // same path as typing "start"

        String q = "QUESTION|q1|2 + 2 = ?|3|4|5|6|2";
        assertEquals(q, alice.readLine());
        assertEquals(q, bob.readLine());

        alice.send("ANSWER|q1|B");                   // correct
        bob.send("ANSWER|q1|C");                     // wrong

        for (TestClient c : List.of(alice, bob)) {
            assertEquals("RESULT|q1|B", c.readLine());
            String scores = c.readLine();
            assertTrue(scores.startsWith("SCORES|alice:"), "alice leads: " + scores);
            assertTrue(scores.endsWith(",bob:0"), "bob has 0: " + scores);
        }
        // END arrives after the engine's 3 s reveal pause.
        assertEquals("END|alice", alice.readLine());
        assertEquals("END|alice", bob.readLine());
    }

    // ── 6 ────────────────────────────────────────────────────────────────

    @Test
    void answerBeforeJoinIsRejected() throws Exception {
        startServer(NO_AUTO_START);
        TestClient c = connect();
        c.send("ANSWER|q1|A");
        assertEquals("ERROR|join first", c.readLine());
    }

    // ── extras ───────────────────────────────────────────────────────────

    @Test
    void lobbyAutoStartsAfterWaitWithTwoPlayers() throws Exception {
        startServer(300);                            // short lobby timer
        TestClient alice = connect();
        TestClient bob = connect();
        alice.send("JOIN|alice");
        assertEquals("WELCOME|alice", alice.readLine());
        bob.send("JOIN|bob");
        assertEquals("WELCOME|bob", bob.readLine());

        assertTrue(alice.readLine().startsWith("QUESTION|q1|"));
        assertTrue(bob.readLine().startsWith("QUESTION|q1|"));

        // Late joiner is told the game has started.
        TestClient late = connect();
        late.send("JOIN|zoe");
        assertEquals("ERROR|game already started", late.readLine());
    }

    @Test
    void malformedLinesGetErrorAndSecondJoinIsIgnored() throws Exception {
        startServer(NO_AUTO_START);
        TestClient c = connect();
        c.send("JOIN");
        assertEquals("ERROR|malformed JOIN", c.readLine());
        c.send("JOIN|bad,name");
        assertEquals("ERROR|invalid name", c.readLine());
        c.send("JOIN|eve");
        assertEquals("WELCOME|eve", c.readLine());
        c.send("JOIN|eve2");                         // ignored: no reply
        c.send("ANSWER|q1");
        assertEquals("ERROR|malformed ANSWER", c.readLine());
    }

    @Test
    void overlongLineDisconnectsOnlyThatClient() throws Exception {
        startServer(NO_AUTO_START);
        TestClient good = connect();
        TestClient bad = connect();
        bad.sendRaw("X".repeat(5_000));              // > 4096-byte read buffer, no newline
        assertNull(bad.readLine(), "server should close the connection");

        good.send("JOIN|frank");
        assertEquals("WELCOME|frank", good.readLine());
    }

    // ── Member 4: reliability ────────────────────────────────────────────

    /**
     * A client floods requests but never reads the replies. Its write queue
     * hits MAX_QUEUED_BYTES and the server drops it; a normal client is
     * unaffected the whole time.
     */
    private static final int FLOOD_CHUNKS = 250;

    @Test
    void clientThatStopsReadingIsDroppedOthersUnaffected() throws Exception {
        startServer(NO_AUTO_START);
        TestClient good = connect();
        TestClient stuck = connect();
        stuck.socket.setReceiveBufferSize(4096);

        // ~1M bad lines → ~22 MB of "ERROR|unknown command" replies nobody reads.
        // Must exceed kernel buffers too: macOS auto-grows send buffers up to 4 MB.
        Thread flooder = new Thread(() -> {
            byte[] chunk = "X\n".repeat(4096).getBytes(StandardCharsets.UTF_8);
            try {
                for (int i = 0; i < FLOOD_CHUNKS; i++) stuck.out.write(chunk);
                stuck.out.flush();
            } catch (IOException expected) {
                // the server hung up on us: that's the point
            }
        }, "flooder");
        flooder.setDaemon(true);
        flooder.start();

        good.send("JOIN|grace");
        assertEquals("WELCOME|grace", good.readLine());

        // Don't read yet: wait until the flood is over, so the client really is stuck.
        flooder.join(10_000);
        assertFalse(flooder.isAlive(), "flooder should finish or be cut off");

        // Now drain what reached it: far fewer replies than requests, then EOF/reset.
        int sentLines = FLOOD_CHUNKS * 4096;
        int lines = 0;
        String l;
        while ((l = stuck.readLine()) != null) {
            assertEquals("ERROR|unknown command", l);
            lines++;
        }
        assertTrue(lines < sentLines, "server should have dropped the stuck client early, got " + lines);

        good.send("NONSENSE");
        assertEquals("ERROR|unknown command", good.readLine(), "good client still served");
    }

    /** A player vanishes (RST) mid-question; the round still ends and the game finishes. */
    @Test
    void playerDroppingMidQuestionDoesNotStallTheGame() throws Exception {
        startServer(NO_AUTO_START);
        TestClient alice = connect();
        TestClient bob = connect();
        alice.send("JOIN|alice");
        assertEquals("WELCOME|alice", alice.readLine());
        bob.send("JOIN|bob");
        assertEquals("WELCOME|bob", bob.readLine());

        server.submitConsoleCommand("start");
        assertTrue(alice.readLine().startsWith("QUESTION|q1|"));
        assertTrue(bob.readLine().startsWith("QUESTION|q1|"));

        bob.socket.setSoLinger(true, 0);              // close() sends RST, like a crash
        bob.close();

        alice.send("ANSWER|q1|B");
        assertEquals("RESULT|q1|B", alice.readLine());
        String scores = alice.readLine();
        assertTrue(scores.startsWith("SCORES|alice:"), scores);
        assertFalse(scores.contains("bob"), "bob left, so he is off the leaderboard: " + scores);
        assertEquals("END|alice", alice.readLine());
    }

    // ── tiny blocking test client ─────────────────────────────────────────

    private static final class TestClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setTcpNoDelay(true);              // send small writes immediately
            socket.setSoTimeout(6_000);              // never block forever on read
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(String line) throws IOException { sendRaw(line + "\n"); }

        void sendRaw(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Next line, or null if the server closed the connection. */
        String readLine() throws IOException {
            try {
                return in.readLine();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("timed out waiting for a line from the server", e);
            } catch (IOException e) {
                return null;                         // connection reset counts as closed
            }
        }

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
