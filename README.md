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
```
javac -d out $(find src -name "*.java")
java -cp out quiz.server.QuizServer
java -cp out quiz.client.QuizClient
```
(The JavaFX GUI will need the JavaFX SDK; setup notes to be added.)

## Git workflow
- `main` is protected by convention: only Member 1 merges.
- Each member works on a branch: `member1-server`, `member2-protocol`, and so on. Open a pull request when a piece works.
