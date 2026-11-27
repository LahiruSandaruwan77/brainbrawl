# BrainBrawl

A real-time multiplayer quiz battle game built with **Java NIO** (non-blocking `Selector`, `ServerSocketChannel`, `SocketChannel`) and a **JavaFX** client.
Developed for IN3111 Network Programming, Assignment 02.

## How it works
- One server handles many players on a single thread using a `Selector`.
- Players join, receive each question at the same time, answer against a timer, and a live leaderboard is broadcast after every round.

## Package layout and ownership

```
src/main/java/quiz/
├── common/   Message.java, Protocol.java            -> Member 2
├── server/   QuizServer.java (Selector loop)        -> Member 1
│             ClientSession.java                     -> Member 4
├── game/     GameEngine.java, Question.java,
│             QuestionLoader.java                    -> Member 3
└── client/   QuizClient.java, GUI classes           -> Member 5
```

| Member | Owns | Network concept demoed (2 min) | Also responsible for |
|---|---|---|---|
| 1 | Server core | `Selector`, `ServerSocketChannel`, non-blocking accept/read | Integration lead, merges to `main` |
| 2 | Protocol and framing | `ByteBuffer` flip/compact, partial reads, newline framing | Protocol spec in report |
| 3 | Game engine | Question loading, timer, scoring, state machine | Test question set, testing section |
| 4 | Broadcast and sessions | `OP_WRITE`, per-client write queues, disconnect handling | Leaderboard logic, reliability testing |
| 5 | Client and GUI | Client `SocketChannel`, `InetSocketAddress`, network/UI threading | Demo script, screenshots, report formatting |

Member names: _fill in_

## Protocol (draft, newline-delimited text)
| Direction | Message |
|---|---|
| C -> S | `JOIN\|name` |
| S -> C | `WELCOME\|playerId` |
| S -> C | `QUESTION\|id\|text\|A\|B\|C\|D\|seconds` |
| C -> S | `ANSWER\|questionId\|choice` |
| S -> C | `RESULT\|questionId\|correctChoice` |
| S -> C | `SCORES\|name:points,name:points,...` |
| S -> C | `END\|winner` |

## Build and run
Requires JDK 17+ and Maven. Run everything from the project root.

```
mvn clean test                      # build + run all tests (engine and server)

mvn compile exec:java               # start the server on port 5050
mvn compile exec:java -Dexec.args="6000 my-questions.txt"   # optional port and/or question file

mvn javafx:run                      # start a JavaFX client (one per player)
```

The server loads questions from the file argument if given, otherwise from
`src/main/resources/questions.txt`, otherwise from `questions.txt` on the classpath.

**Starting a game.** The game starts automatically once at least 2 players have
joined and 15 s have passed since the first join (`QuizServer.LOBBY_WAIT_MS`).
You can also type commands in the server console:

| Command  | Effect |
|---|---|
| `start`  | start the game now (needs at least 1 player) |
| `status` | print the game state and lobby |
| `quit`   | stop the server |

**Without Maven** (server only, no JavaFX needed):
```
javac -d out src/main/java/quiz/common/*.java src/main/java/quiz/game/*.java src/main/java/quiz/server/*.java
java -cp out quiz.server.QuizServer
```
A quick manual test without the GUI: `ncat localhost 5050` (or `telnet`), then type `JOIN|alice`.

## Git workflow
- `main` is protected by convention: only Member 1 merges.
- Each member works on a branch: `member1-server`, `member2-protocol`, and so on. Open a pull request when a piece works.
