package quiz.server;

import quiz.common.Message;
import quiz.common.Protocol;
import quiz.game.GameListener;
import quiz.game.Question;
import quiz.game.QuestionLoader;
import quiz.game.QuizGameEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owner: Member 1.
 *
 * <h2>BrainBrawl server: many clients on ONE thread with a NIO {@link Selector}</h2>
 *
 * <pre>
 *                    ┌───────────────── Selector ─────────────────┐
 *   new client ───►  │ ServerSocketChannel   OP_ACCEPT            │
 *   client 1   ───►  │ SocketChannel #1      OP_READ (+OP_WRITE)  │ ──► one thread
 *   client 2   ───►  │ SocketChannel #2      OP_READ (+OP_WRITE)  │     runs the loop
 *   ...              └────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The loop in {@link #run()} does this forever:
 * <ol>
 *   <li>{@code selector.select(200)} — sleep until a channel is ready OR 200 ms pass.
 *       We never block forever, because the game engine is time-driven.</li>
 *   <li>For each ready key: accept a new client, read from a client, or finish
 *       writing to a client whose socket buffer was full.</li>
 *   <li>Run console commands (typed on another thread, handed over via a queue).</li>
 *   <li>{@code engine.tick(now)} — lets the engine end rounds / move to the next question.</li>
 *   <li>Auto-start the game when the lobby rule is met.</li>
 * </ol>
 *
 * <p>Because everything happens on one thread, the engine and the session maps
 * need no locks. The only cross-thread hand-off is the console command queue.
 *
 * <p>This class is also the engine's {@link GameListener}: when the engine wants
 * to send something, it calls {@link #broadcast} / {@link #sendTo}, and we turn
 * the {@link Message} into bytes on each client's write queue.
 */
public class QuizServer implements GameListener, Runnable, AutoCloseable {

    // ── Tunables ──────────────────────────────────────────────────────────

    /** Lobby auto-start: wait this long after the FIRST join (and need ≥ 2 players). */
    public static final long LOBBY_WAIT_MS = 15_000L;

    /** Minimum players for the automatic lobby start. ("start" on the console ignores this.) */
    public static final int MIN_PLAYERS = 2;

    /** select() timeout: the engine is ticked at least this often. */
    private static final long SELECT_TIMEOUT_MS = 200L;

    /** Longest player name we accept. */
    private static final int MAX_NAME_LENGTH = 20;

    // TODO(Member 2): add ERROR to Protocol and use Protocol.ERROR here.
    private static final String ERROR = "ERROR";

    // ── NIO core ──────────────────────────────────────────────────────────

    private final Selector selector;
    private final ServerSocketChannel serverChannel;

    // ── Game + players ────────────────────────────────────────────────────

    private final QuizGameEngine engine;
    private final long lobbyWaitMs;

    /** Joined players only, by username. Connected-but-not-joined clients live only in the Selector. */
    private final Map<String, ClientSession> sessionsByName = new LinkedHashMap<>();

    /** Time of the first successful JOIN in the current lobby, or -1 if the lobby is empty. */
    private long firstJoinAt = -1L;

    /** Last engine state we printed, so state changes show up on the console. */
    private QuizGameEngine.State lastPrintedState;

    /**
     * Clients whose write failed while the engine was in the middle of a
     * broadcast. We close them after the callback returns, so we never call
     * engine.leave() re-entrantly from inside the engine.
     */
    private final List<ClientSession> pendingCloses = new ArrayList<>();

    // ── Thread hand-off and shutdown ──────────────────────────────────────

    /** Console thread → selector thread. Thread-safe queue + selector.wakeup(). */
    private final Queue<String> consoleCommands = new ConcurrentLinkedQueue<>();

    private volatile boolean running = true;
    private volatile boolean loopStarted = false;
    private final CountDownLatch loopFinished = new CountDownLatch(1);

    // ══════════════════════════════════════════════════════════════════════
    //  Start-up
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Opens the Selector and binds the listening socket right away, so the port
     * is ready (and {@link #getPort()} is known) before {@link #run()} starts.
     *
     * @param port        TCP port, or 0 for "any free port" (used by tests)
     * @param questions   questions for this game
     * @param lobbyWaitMs lobby auto-start delay (normally {@link #LOBBY_WAIT_MS})
     */
    public QuizServer(int port, List<Question> questions, long lobbyWaitMs) throws IOException {
        this.engine = new QuizGameEngine(questions, this);   // engine calls back into us
        this.lobbyWaitMs = lobbyWaitMs;
        this.lastPrintedState = engine.getState();

        // 1. One Selector watches every channel.
        selector = Selector.open();

        // 2. Listening socket, NON-BLOCKING, registered for OP_ACCEPT.
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);              // required before register()
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** The port we are actually listening on (useful when constructed with port 0). */
    public int getPort() {
        return serverChannel.socket().getLocalPort();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  The Selector loop
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void run() {
        loopStarted = true;
        log("Listening on port " + getPort() + " — waiting for players.");
        try {
            while (running) {
                // Wait for ready channels, but at most 200 ms so the game clock keeps moving.
                selector.select(SELECT_TIMEOUT_MS);
                if (!running) break;

                // Handle every channel that is ready right now.
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();                 // must remove, or we'd see it again next time
                    handleKey(key);
                }

                runConsoleCommands();

                // Drive the time-based game engine after EVERY wake-up.
                long now = System.currentTimeMillis();
                engine.tick(now);
                maybeAutoStart(now);

                closePendingClients();
                printStateChange();
            }
        } catch (IOException e) {
            log("Selector failed: " + e.getMessage());
        } finally {
            closeEverything();
            loopFinished.countDown();
            log("Server stopped.");
        }
    }

    /** Dispatch one ready key. One bad client must never take the server down. */
    private void handleKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isAcceptable()) accept();
            if (key.isValid() && key.isReadable())   read(key);
            if (key.isValid() && key.isWritable())   flush((ClientSession) key.attachment());
        } catch (IOException | RuntimeException e) {
            // Anything unexpected from this client: drop the client, keep serving others.
            if (key.attachment() instanceof ClientSession s) {
                disconnect(s, "error: " + e);
            } else {
                log("Accept failed: " + e);
            }
        }
    }

    // ── OP_ACCEPT ─────────────────────────────────────────────────────────

    /** A new client is waiting: accept it, make it non-blocking, watch it for OP_READ. */
    private void accept() throws IOException {
        SocketChannel client = serverChannel.accept();   // non-blocking: may return null
        if (client == null) return;

        client.configureBlocking(false);
        ClientSession session = new ClientSession(client);
        // The session rides along as the key's attachment, so read() can find it.
        client.register(selector, SelectionKey.OP_READ, session);
        log("Connection from " + client.getRemoteAddress());
    }

    // ── OP_READ ───────────────────────────────────────────────────────────

    /**
     * Read whatever bytes have arrived and process every COMPLETE line.
     *
     * <p>TCP is a byte stream: one read() may hold half a line, or three lines.
     * The read buffer keeps leftover bytes between calls:
     * <pre>
     *   read()   → bytes appended to buffer        (buffer in "write mode")
     *   flip()   → switch to "read mode"
     *   take each "...\n" line out of it
     *   compact()→ move the unfinished tail to the front, back to "write mode"
     * </pre>
     */
    // TODO(Member 2): replace the line extraction with Protocol's frame reader.
    private void read(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        ByteBuffer buf = session.getReadBuffer();

        int n = session.getChannel().read(buf);
        if (n == -1) {                                   // client closed its socket
            disconnect(session, "client closed connection");
            return;
        }

        buf.flip();
        int newline;
        while ((newline = indexOf(buf, (byte) Protocol.TERMINATOR)) >= 0) {
            byte[] lineBytes = new byte[newline - buf.position()];
            buf.get(lineBytes);
            buf.get();                                   // skip the '\n' itself
            String line = new String(lineBytes, StandardCharsets.UTF_8);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);  // tolerate CRLF

            handleLine(session, line);
            if (!session.getChannel().isOpen()) return;  // handling may have closed it
        }
        buf.compact();

        // Buffer completely full and still no '\n' → line is too long. Protect the server.
        if (!buf.hasRemaining()) {
            disconnect(session, "line longer than " + buf.capacity() + " bytes");
        }
    }

    /** Position of the first {@code b} between position and limit, or -1. */
    private static int indexOf(ByteBuffer buf, byte b) {
        for (int i = buf.position(); i < buf.limit(); i++) {
            if (buf.get(i) == b) return i;
        }
        return -1;
    }

    // ── Protocol handling ─────────────────────────────────────────────────

    /** Handle one complete text line from a client. */
    private void handleLine(ClientSession session, String line) {
        if (line.isBlank()) return;                      // ignore empty keep-alive lines

        // TODO(Member 2): replace with Message.decode(line).
        String[] parts = line.split("\\|", -1);
        switch (parts[0]) {
            case Protocol.JOIN   -> handleJoin(session, parts);
            case Protocol.ANSWER -> handleAnswer(session, parts);
            default              -> sendError(session, "unknown command");
        }
    }

    /** JOIN|name */
    private void handleJoin(ClientSession session, String[] parts) {
        if (session.getName() != null) {
            log(session.getName() + " sent a second JOIN — ignored.");
            return;
        }
        if (parts.length != 2) {
            sendError(session, "malformed JOIN");
            return;
        }

        String name = parts[1].strip();
        // Pick a helpful reason first; the engine has the final say.
        if (engine.getState() != QuizGameEngine.State.LOBBY) {
            sendError(session, "game already started");
            return;
        }
        if (!isValidName(name)) {
            sendError(session, "invalid name");
            return;
        }
        if (!engine.join(name)) {
            sendError(session, "name taken");
            return;
        }

        session.setName(name);
        sessionsByName.put(name, session);
        if (firstJoinAt < 0) firstJoinAt = System.currentTimeMillis();

        send(session, new Message(Protocol.WELCOME, name));
        log(name + " joined.");
        printLobbyStatus();
    }

    /**
     * Server-side name check. The engine already rejects blank names, '|' and
     * newlines. We also reject ',' and ':' because SCORES is "name:pts,name:pts".
     * TODO(Member 3): once the engine rejects ',' and ':' this is just a safety net.
     */
    private static boolean isValidName(String name) {
        return !name.isEmpty()
                && name.length() <= MAX_NAME_LENGTH
                && name.chars().noneMatch(c -> c == '|' || c == ',' || c == ':' || Character.isISOControl(c));
    }

    /** ANSWER|questionId|choice */
    private void handleAnswer(ClientSession session, String[] parts) {
        if (session.getName() == null) {
            sendError(session, "join first");
            return;
        }
        if (parts.length != 3) {
            sendError(session, "malformed ANSWER");
            return;
        }
        // The engine silently ignores wrong question ids, late or duplicate answers.
        engine.submitAnswer(session.getName(), parts[1], parts[2].strip());
    }

    // ── Lobby / game start ────────────────────────────────────────────────

    /** Auto-start: ≥ MIN_PLAYERS joined and the first join was ≥ lobbyWaitMs ago. */
    private void maybeAutoStart(long now) {
        if (engine.getState() != QuizGameEngine.State.LOBBY) return;
        if (sessionsByName.size() < MIN_PLAYERS) return;
        if (firstJoinAt < 0 || now - firstJoinAt < lobbyWaitMs) return;
        startGame("lobby timer");
    }

    private void startGame(String reason) {
        if (engine.getState() != QuizGameEngine.State.LOBBY) {
            log("Cannot start: game is " + engine.getState() + ".");
            return;
        }
        if (sessionsByName.isEmpty()) {
            log("Cannot start: no players have joined.");
            return;
        }
        log("Starting game (" + reason + ") with " + sessionsByName.keySet());
        engine.start();   // engine broadcasts the first QUESTION through us
    }

    private void printLobbyStatus() {
        if (engine.getState() != QuizGameEngine.State.LOBBY) return;
        int n = sessionsByName.size();
        String status = "Lobby: " + n + " player(s) " + sessionsByName.keySet();
        if (n < MIN_PLAYERS) {
            status += " — waiting for at least " + MIN_PLAYERS + " players (or type 'start').";
        } else {
            long left = Math.max(0, lobbyWaitMs - (System.currentTimeMillis() - firstJoinAt));
            status += " — auto-start in " + (left + 999) / 1000 + "s (or type 'start').";
        }
        log(status);
    }

    private void printStateChange() {
        QuizGameEngine.State now = engine.getState();
        if (now != lastPrintedState) {
            log("Game state: " + lastPrintedState + " -> " + now);
            lastPrintedState = now;
        }
    }

    // ── Console commands (other thread → selector thread) ────────────────

    /**
     * Thread-safe: queue a console command for the selector thread and wake it
     * up so it runs straight away instead of after the select() timeout.
     * Also used by tests to type "start".
     */
    public void submitConsoleCommand(String command) {
        consoleCommands.add(command.strip());
        selector.wakeup();
    }

    /** Runs on the selector thread. */
    private void runConsoleCommands() {
        String cmd;
        while ((cmd = consoleCommands.poll()) != null) {
            switch (cmd.toLowerCase()) {
                case "start"  -> startGame("console");
                case "status" -> {
                    log("State " + engine.getState() + ", players " + sessionsByName.keySet());
                    printLobbyStatus();
                }
                case "quit", "stop" -> stop();
                case ""       -> { }
                default       -> log("Commands: start | status | quit");
            }
        }
    }

    /** Daemon thread reading System.in; it only enqueues, never touches game state. */
    private void startConsoleThread() {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    submitConsoleCommand(line);
                }
            } catch (IOException ignored) {
                // console closed — server keeps running
            }
        }, "console-reader");
        t.setDaemon(true);   // don't keep the JVM alive just for the console
        t.start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GameListener: engine → network
    // ══════════════════════════════════════════════════════════════════════

    /** Send to every JOINED player. */
    @Override
    public void broadcast(Message m) {
        // Copy: a failed write may queue a close, and we never modify while iterating.
        for (ClientSession s : new ArrayList<>(sessionsByName.values())) {
            send(s, m);
        }
    }

    /** Send to one player by username (ignored if they've left). */
    @Override
    public void sendTo(String player, Message m) {
        ClientSession s = sessionsByName.get(player);
        if (s != null) send(s, m);
    }

    // ── Non-blocking writes (OP_WRITE) ────────────────────────────────────

    private void sendError(ClientSession session, String reason) {
        send(session, new Message(ERROR, reason));
    }

    /** Queue a message for a client and try to write it now. */
    private void send(ClientSession session, Message m) {
        if (!session.getChannel().isOpen()) return;
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(encode(m));
        session.getWriteQueue().add(bytes);
        try {
            flush(session);
        } catch (IOException e) {
            // We may be inside an engine callback: close later, not now.
            pendingCloses.add(session);
        }
    }

    /**
     * Write as much of the client's queue as the socket accepts right now.
     *
     * <p>A non-blocking write() may write only PART of a buffer when the
     * client's TCP send buffer is full (slow client). Then we register
     * OP_WRITE and the Selector tells us when there is room again. When the
     * queue is empty we remove OP_WRITE, otherwise select() would wake up
     * constantly (a socket is almost always writable).
     */
    // TODO(Member 4): replace with their ClientSession write logic.
    private void flush(ClientSession session) throws IOException {
        SelectionKey key = session.getChannel().keyFor(selector);
        if (key == null || !key.isValid()) return;

        Queue<ByteBuffer> queue = session.getWriteQueue();
        while (!queue.isEmpty()) {
            ByteBuffer head = queue.peek();
            session.getChannel().write(head);
            if (head.hasRemaining()) {
                // Socket full: ask the Selector to wake us when it's writable again.
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                return;
            }
            queue.poll();                       // fully written, next one
        }
        // Everything sent: stop listening for OP_WRITE.
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    /** TYPE|f1|f2...\n — TODO(Member 2): replace with Message.encode(). */
    private static String encode(Message m) {
        String[] fields = m.getFields();
        if (fields == null || fields.length == 0) return m.getType() + Protocol.TERMINATOR;
        return m.getType() + Protocol.DELIMITER + String.join("|", fields) + Protocol.TERMINATOR;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Disconnect and shutdown
    // ══════════════════════════════════════════════════════════════════════

    /** Remove a client cleanly: tell the engine, cancel the key, close the socket. */
    private void disconnect(ClientSession session, String reason) {
        SocketChannel ch = session.getChannel();
        if (!ch.isOpen()) return;                       // already done

        String name = session.getName();
        if (name != null) {
            sessionsByName.remove(name);
            engine.leave(name);
        }

        SelectionKey key = ch.keyFor(selector);
        if (key != null) key.cancel();                  // stop watching this channel
        try { ch.close(); } catch (IOException ignored) { }

        log((name != null ? name : "Unjoined client") + " disconnected (" + reason + ").");
        if (sessionsByName.isEmpty()) firstJoinAt = -1L; // empty lobby: restart the timer
        if (name != null) printLobbyStatus();
    }

    private void closePendingClients() {
        for (ClientSession s : pendingCloses) disconnect(s, "write failed");
        pendingCloses.clear();
    }

    /** Thread-safe: ask the loop to exit. It wakes the selector so this is immediate. */
    public void stop() {
        running = false;
        selector.wakeup();
    }

    /**
     * Stop the server and wait (up to 5 s) until every channel is closed.
     * Safe to call even if {@link #run()} was never started.
     */
    @Override
    public void close() {
        stop();
        if (loopStarted) {
            try {
                loopFinished.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            closeEverything();
        }
    }

    /** Close all client channels, the listening channel and the Selector. */
    private void closeEverything() {
        if (selector.isOpen()) {
            for (SelectionKey key : selector.keys()) {
                try { key.channel().close(); } catch (IOException ignored) { }
            }
        }
        try { serverChannel.close(); } catch (IOException ignored) { }
        try { selector.close(); } catch (IOException ignored) { }
        sessionsByName.clear();
    }

    private static void log(String msg) {
        System.out.println("[server] " + msg);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  main
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Usage: {@code QuizServer [port] [questionsFile]} (either order, both optional).
     * A numeric argument is the port; anything else is the question file.
     */
    public static void main(String[] args) throws Exception {
        int port = Protocol.PORT;
        String questionsFile = null;
        for (String a : args) {
            if (a.matches("\\d+")) port = Integer.parseInt(a);
            else questionsFile = a;
        }

        List<Question> questions = loadQuestions(questionsFile);
        log("Loaded " + questions.size() + " questions.");

        QuizServer server = new QuizServer(port, questions, LOBBY_WAIT_MS);
        log("Console commands: start | status | quit");
        server.startConsoleThread();
        server.run();                                    // the Selector loop runs on main
    }

    /**
     * Question source, in order: the file given on the command line,
     * src/main/resources/questions.txt (running from the project root),
     * then questions.txt on the classpath (e.g. inside the jar).
     */
    static List<Question> loadQuestions(String fileArg) throws IOException {
        if (fileArg != null) {
            return QuestionLoader.load(Path.of(fileArg));
        }
        Path projectFile = Path.of("src", "main", "resources", "questions.txt");
        if (Files.isRegularFile(projectFile)) {
            return QuestionLoader.load(projectFile);
        }
        // QuestionLoader needs a Path; a classpath resource may be inside a jar,
        // so copy it to a temp file first.
        try (InputStream in = QuizServer.class.getResourceAsStream("/questions.txt")) {
            if (in == null) throw new IOException("questions.txt not found (give a file path argument)");
            Path tmp = Files.createTempFile("brainbrawl-questions", ".txt");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return QuestionLoader.load(tmp);
        }
    }
}
