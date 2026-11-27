# Member 3 — Report Notes

**Module:** Game Engine (`quiz.game`)  
**Files owned:** `Question.java`, `GameListener.java`, `GameEngine.java`, `QuestionLoader.java`,
`QuizGameEngine.java`, `ConsoleDemo.java`, `questions.txt`, `QuizGameEngineTest.java`

---

## 1. State Machine Diagram (ASCII)

```
                     join() ×N
                        │
                        ▼
                   ┌─────────┐
              ┌───►│  LOBBY  │
              │    └────┬────┘
              │         │ start()
              │         │ (≥1 player)
              │         ▼
              │    ┌──────────┐  endRound()
              │    │ QUESTION │─────────────────────────────┐
              │    │          │                             │
              │    │ deadline = now + timeLimitSec×1000     │
              │    │ roundAnswers = {}                      │
              │    └──────────┘                             │
              │         │                                   │
              │  tick() checks:                             ▼
              │  • now >= deadline                    ┌──────────┐
              │  • all players answered               │  REVEAL  │
              │                                       │ 3-second │
              │                                       │  pause   │
              │    ┌──────────┐                       └────┬─────┘
              │    │ FINISHED │◄──── no more questions ────┘
              │    └──────────┘                │
              │                     more questions remain
              └─────────────────────────────────┘
                    sendQuestion(next index)
```

**States and invariants:**
- **LOBBY** — players can join; engine waits for `start()`
- **QUESTION** — timer running; `submitAnswer()` is accepted; `tick()` checks deadline and `allIn`
- **REVEAL** — engine waits 3000 ms so clients can show the correct answer before moving on
- **FINISHED** — terminal state; `tick()` and `submitAnswer()` are no-ops

---

## 2. `tick()` Snippet (key logic, ~15 lines)

```java
// QuizGameEngine.java — tick(long nowMillis)

@Override
public void tick(long nowMillis) {
    lastTickMs = nowMillis;                       // (1) record injected time

    if (state == State.QUESTION) {
        boolean timeUp = nowMillis >= deadline;   // (2) deadline expired?
        boolean allIn  = !scores.isEmpty()
                         && roundAnswers.size() == scores.size(); // all answered?
        if (timeUp || allIn) {
            endRound();                           // (3) score + broadcast
        }

    } else if (state == State.REVEAL) {
        if (nowMillis >= revealUntil) {
            advanceQuestion(nowMillis);           // (4) next question or FINISHED
        }
    }
    // LOBBY / FINISHED: no-op — guards are implicit via the if-chain
}
```

**Why this works without threads:**
The server's `Selector` wakes up on I/O events and also times out roughly every 1000 ms.
On each wake-up it calls `tick(System.currentTimeMillis())`.  Because the engine never
calls `System.currentTimeMillis()` itself, its behaviour is fully deterministic when
tested — the test just passes in hand-picked timestamps.

---

## 3. Scoring Snippet (~10 lines)

```java
// QuizGameEngine.java — endRound(), scoring section

String correctLetter = indexToLetter(current.correctIndex()); // e.g. "C"
long   limitMs       = current.timeLimitSec() * 1_000L;

for (String player : new ArrayList<>(scores.keySet())) {
    AnswerRecord rec = roundAnswers.get(player);
    if (rec == null) continue;                   // no answer → 0 pts, skip

    long latency = rec.timeMs() - questionStartMs;
    totalAnswerMs.merge(player, latency, Long::sum); // accumulate for tie-break

    if (correctLetter.equals(rec.choice())) {
        // Speed bonus: answered with 8/10 s remaining → +400 bonus
        long remainingMs = Math.max(0L, Math.min(limitMs, deadline - rec.timeMs()));
        int  pts         = 1000 + (int) (500L * remainingMs / limitMs);
        scores.merge(player, pts, Integer::sum);
    }
    // wrong answer: latency accumulated, 0 points added
}
```

**Example (q01, 12-second limit, Alice answers at 2.4 s):**
```
remainingMs = 12000 − 2400 = 9600
score       = 1000 + ⌊500 × 9600 / 12000⌋ = 1000 + 400 = 1400
```

---

## 4. Design Problem and Solution

**Problem: `start()` has no time parameter, but the deadline requires the current time.**

The `GameEngine` interface specifies `void start()` with no timestamp argument, yet internally
the engine must set `deadline = now + timeLimitSec × 1000`.  Calling `System.currentTimeMillis()`
here would break deterministic testing.

**Solution: store `lastTickMs` updated on every `tick()`.**

```java
private long lastTickMs = 0L;   // set by tick(); used by start()

public void tick(long nowMillis) {
    lastTickMs = nowMillis;
    // ...
}

public void start() {
    // ...
    sendQuestion(lastTickMs);   // uses injected time, not wall clock
}
```

The test simply calls `engine.tick(0)` before `engine.start()`, which sets `lastTickMs = 0`
so the deadline is exactly `timeLimitSec × 1000`.  Production code calls `tick(now)` on every
Selector wake-up, so `lastTickMs` is always within one selector interval of the real time — 
accurate enough for a 10-second question timer.

---

## 5. Two-Minute Demo Script

| Time | Action | What to show |
|------|--------|--------------|
| 0:00 | Open `questions.txt` | "10 pipe-delimited questions; `id|text|A|B|C|D|correctIndex|timeLimitSec`" |
| 0:15 | Open `QuestionLoader.java` | "Two loading methods: `Files.readAllLines()` vs `FileChannel + ByteBuffer.flip()`" |
| 0:30 | Open `QuizGameEngine.java`, show state enum | "Four states: LOBBY → QUESTION → REVEAL → FINISHED. All transitions are in `tick()`." |
| 0:45 | Show `tick()` method | "`tick(now)` is called by the Selector on every wake-up. No `System.currentTimeMillis()` — time is always injected." |
| 1:00 | Show `endRound()` scoring | "Correct = 1000 + speed bonus capped at 500. Wrong/late = 0. `totalAnswerMs` for tie-breaking." |
| 1:15 | Run `mvn test` | "24 tests, 0 failures — covers duplicates, late answers, tie-breaks, full game flow." |
| 1:35 | Run `ConsoleDemo` | "Fake clock: Alice fast → 4317 pts, Bob slow → 2570, Charlie misses Q3 → 1355. WINNER: Alice." |
| 1:55 | Show `getRanking()` comparator | "Score desc → total latency asc → alphabetical. Fully deterministic, no random tie-break." |

**Key talking points for the examiner:**
1. The engine is a pure state machine — no threads, no I/O, testable in milliseconds.
2. `FileChannel + ByteBuffer.flip()` mirrors exactly how the server reads from `SocketChannel`.
3. Time injection (via `tick()`) is the same technique used in production game servers to
   avoid flaky timer-dependent tests.
