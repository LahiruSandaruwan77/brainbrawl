# Member 3 — Testing and Results

**Module:** Game Engine (`quiz.game`)  
**Test class:** `QuizGameEngineTest` (JUnit 5)  
**Run command:** `mvn test`  
**Result:** 24 tests run, 0 failures, 0 errors  
**Total time:** 0.398 s

---

## Test Results Table

| # | Scenario | Test Method | Steps | Expected | Actual |
|---|----------|-------------|-------|----------|--------|
| 1 | Duplicate username rejected (exact) | `duplicateUsernameRejected` | join("Alice") twice | second join returns `false` | PASS |
| 2 | Duplicate username rejected (case-insensitive) | `duplicateUsernameRejected` | join("Alice") then join("alice") | `false` | PASS |
| 3 | Duplicate username rejected (upper-case) | `duplicateUsernameRejected` | join("Alice") then join("ALICE") | `false` | PASS |
| 4 | Blank username rejected | `blankAndNullUsernameRejected` | join(""), join("   "), join(null) | all return `false` | PASS |
| 5 | Username with pipe char rejected | `nameWithPipeOrNewlineRejected` | join("ali\|ce") | `false` | PASS |
| 6 | Username with newline rejected | `nameWithPipeOrNewlineRejected` | join("ali\nce") | `false` | PASS |
| 7 | Join after game has started | `joinAfterStartRejected` | join → start → join again | `false` | PASS |
| 8 | start() with empty lobby does nothing | `startWithEmptyLobbyDoesNothing` | call start() with no players | state stays LOBBY, no messages | PASS |
| 9 | start() sends first QUESTION message | `startSendsFirstQuestion` | join Alice → start | 1 QUESTION message, id="q1" | PASS |
| 10 | Late answer scores 0 | `lateAnswerScoresZero` | tick past deadline, then submitAnswer | score = 0 | PASS |
| 11 | Double answer: only first counts | `doubleAnswerOnlyCountsFirst` | submit correct "B", then wrong "A" | score > 1000 (first answer counted) | PASS |
| 12 | Answer for wrong question id ignored | `answerForWrongQuestionIdIgnored` | submit with qId="q2" when current is "q1" | score = 0 | PASS |
| 13 | Wrong answer scores 0 | `wrongAnswerScoresZero` | submit "A" when correct is "B" | score = 0 | PASS |
| 14 | All players answer early ends round | `allPlayersAnswerEarlyEndsRoundBeforeDeadline` | 2 players both answer at t=2000 | REVEAL state, 1 ROUND_END message before deadline | PASS |
| 15 | Faster correct answer scores more | `fasterCorrectAnswerScoresMore` | Alice at t=1000, Bob at t=9000, both correct | Alice ranked first with higher score | PASS |
| 16 | Player leaves mid-round without crash | `playerLeavesMidRoundNoCrash` | join 2, start, Alice answers, Bob leaves | game reaches REVEAL, no exception | PASS |
| 17 | Leave unknown player does not crash | `leaveUnknownPlayerDoesNotCrash` | call leave("nobody") and leave(null) | no exception thrown | PASS |
| 18 | Tie-break: lower total answer time wins | `tiebreakerFasterTotalAnswerTimeWins` | both answer wrong; Alice faster | both score 0; Alice ranked first | PASS |
| 19 | Tie-break: alphabetical when score and time identical | `tiebreakerAlphabeticalWhenScoreAndTimeIdentical` | "Zara" and "Ann" answer at same tick | "Ann" ranks first (alphabetical) | PASS |
| 20 | Single-player game ends with winner | `singlePlayerGameEndsWithWinner` | 1 player, finish game | FINAL message; WINNER=Solo | PASS |
| 21 | Malformed line gives clear error with line number | `malformedLineGivesClearErrorWithLineNumber` | temp file with bad line 2 | `IllegalArgumentException` message contains "2" | PASS |
| 22 | correctIndex out of range gives clear error | `correctIndexOutOfRangeGivesClearError` | correctIndex=5 on line 1 | `IllegalArgumentException` naming the bad field | PASS |
| 23 | Comments and blank lines skipped | `commentsAndBlankLinesAreSkipped` | file with #comments and blank lines | 1 question parsed | PASS |
| 24 | loadWithChannel produces same result as load | `loadWithChannelProducesSameResultAsLoad` | load same file both ways | identical ids, indices, time limits | PASS |
| 25 | Full game message order | `fullGameProducesMessagesInCorrectOrder` | 2 players, 2 questions | QUESTION→ROUND_END→LEADERBOARD×2→FINAL in strict order; Alice wins | PASS |
| 26 | tick() in LOBBY/FINISHED is no-op | `tickInLobbyOrFinishedDoesNothing` | tick with large time in LOBBY then in FINISHED | no messages sent | PASS |
| 27 | getRanking returns a new list each call | `getRankingReturnsDefensiveCopy` | call getRanking twice | different list objects (defensive copy) | PASS |

> **Note:** JUnit reports 24 tests because `duplicateUsernameRejected` has 3 assertions but counts as 1 test. The table above breaks them out for clarity.

---

## ConsoleDemo Output (abridged)

```
loadWithChannel() -> 10 questions loaded
load()            -> 10 questions loaded
Using first 3 questions for the demo.

LOBBY
  join("Alice")   -> true
  join("Bob")     -> true
  join("Charlie") -> true
  join("alice")   -> false   (duplicate, case-insensitive)
  join("bad|name")-> false   (pipe char rejected)
  join("")        -> false   (blank rejected)

ROUND 1 — q01 (limit=12s, correct=C)
  t= 2400ms  Alice  submits C (correct, fast)
  t= 8400ms  Bob    submits C (correct, slow)
  t=12001ms  DEADLINE passed — Charlie timed out
  [BROADCAST] ROUND_END|q01|C
  [BROADCAST] LEADERBOARD|Alice:1400|Bob:1150|Charlie:0

ROUND 2 — q02 (limit=10s, correct=C)
  t=16001ms  Alice  submits C (correct)
  t=17001ms  Bob    submits A (wrong)
  t=18001ms  Charlie submits C (correct)
  Round ends early (all answered before deadline)
  [BROADCAST] LEADERBOARD|Alice:2855|Charlie:1355|Bob:1150

ROUND 3 — q03 (limit=12s, correct=C)
  t=22002ms  Alice  submits C (correct)
  t=23002ms  Bob    submits C (correct)
  t=33103ms  DEADLINE — Charlie submits too late (ignored)
  [BROADCAST] LEADERBOARD|Alice:4317|Bob:2570|Charlie:1355

[BROADCAST] FINAL|Alice:4317|Bob:2570|Charlie:1355|WINNER|Alice

FINAL RESULTS
  Alice       4317
  Bob         2570
  Charlie     1355
```

---

## Score Verification (Round 1)

| Player | Answer time | Remaining ms | Calculation | Score |
|--------|-------------|-------------|-------------|-------|
| Alice | 2400 ms | 12000 − 2400 = 9600 | 1000 + ⌊500×9600/12000⌋ = 1000+400 | **1400** |
| Bob | 8400 ms | 12000 − 8400 = 3600 | 1000 + ⌊500×3600/12000⌋ = 1000+150 | **1150** |
| Charlie | (timeout) | — | 0 | **0** |
