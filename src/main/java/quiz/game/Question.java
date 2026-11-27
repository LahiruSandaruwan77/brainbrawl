package quiz.game;

import java.util.List;

/**
 * Owner: Member 3.
 * An immutable multiple-choice question. Options always has exactly 4 entries (A–D).
 *
 * @param id           unique string identifier used in protocol messages
 * @param text         the question text shown to players
 * @param options      exactly 4 answer choices [A, B, C, D]
 * @param correctIndex 0-based index into options (0=A … 3=D)
 * @param timeLimitSec how long players have to answer
 */
public record Question(
    String       id,
    String       text,
    List<String> options,
    int          correctIndex,
    int          timeLimitSec
) {}
