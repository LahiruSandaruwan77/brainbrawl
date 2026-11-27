package quiz.game;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner: Member 3.
 * Loads {@link Question} objects from a pipe-delimited UTF-8 text file.
 *
 * <p>Line format (8 pipe-separated fields):
 * <pre>
 *   id | text | optA | optB | optC | optD | correctIndex | timeLimitSec
 * </pre>
 * Lines that are blank or start with {@code #} are skipped.
 * Any validation failure throws {@link IllegalArgumentException} with the
 * 1-based line number so the caller can report exactly where the bad data is.
 *
 * <p>Two loading strategies are provided to demonstrate different NIO APIs:
 * <ul>
 *   <li>{@link #load(Path)} — {@code java.nio.file.Files.readAllLines()} (high-level)</li>
 *   <li>{@link #loadWithChannel(Path)} — {@code FileChannel} + {@code ByteBuffer} (low-level)</li>
 * </ul>
 */
public final class QuestionLoader {

    private QuestionLoader() {}   // utility class — no instances

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Load questions using the high-level {@link Files#readAllLines(Path)} API.
     *
     * @param file path to the question file
     * @return parsed, validated list of questions (never null, may be empty)
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if any data line is malformed
     */
    public static List<Question> load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return parse(lines);
    }

    /**
     * Load questions using the low-level {@link FileChannel} + {@link ByteBuffer} API.
     *
     * <p>This demonstrates the same NIO channel concept used for socket I/O:
     * allocate a buffer, read a chunk, flip() to switch from write-mode to
     * read-mode, decode, then clear() to reset for the next read.
     *
     * @param file path to the question file
     * @return parsed, validated list of questions
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if any data line is malformed
     */
    public static List<Question> loadWithChannel(Path file) throws IOException {
        // Pre-size a buffer for the whole file so we handle multi-byte UTF-8
        // sequences that could otherwise be split across buffer boundaries.
        long fileSize = Files.size(file);
        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(fileSize, Integer.MAX_VALUE));

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            // Fill the buffer; loop handles files larger than a single read().
            while (buf.hasRemaining()) {
                int n = ch.read(buf);
                if (n == -1) break;      // EOF
            }
        }

        buf.flip();   // switch from write-mode to read-mode (key NIO step)
        String content = StandardCharsets.UTF_8.decode(buf).toString();
        List<String> lines = List.of(content.split("\r?\n", -1));
        return parse(lines);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Parse and validate a list of raw text lines into Question objects.
     * Throws {@link IllegalArgumentException} with a descriptive message
     * that includes the 1-based line number on the first bad line found.
     */
    private static List<Question> parse(List<String> lines) {
        List<Question> questions = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            int    lineNum = i + 1;
            String line    = lines.get(i).strip();

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Split on '|', keeping empty trailing fields (-1 limit)
            String[] f = line.split("\\|", -1);
            if (f.length != 8) {
                throw new IllegalArgumentException(
                    "Line " + lineNum + ": expected 8 pipe-separated fields, got "
                    + f.length + "  →  \"" + line + "\"");
            }

            String id   = f[0].strip();
            String text = f[1].strip();
            String optA = f[2].strip();
            String optB = f[3].strip();
            String optC = f[4].strip();
            String optD = f[5].strip();

            requireNonEmpty(id,   lineNum, "id");
            requireNonEmpty(text, lineNum, "text");
            requireNonEmpty(optA, lineNum, "option A");
            requireNonEmpty(optB, lineNum, "option B");
            requireNonEmpty(optC, lineNum, "option C");
            requireNonEmpty(optD, lineNum, "option D");

            int correctIndex = parseInt(f[6].strip(), lineNum, "correctIndex");
            int timeLimitSec = parseInt(f[7].strip(), lineNum, "timeLimitSec");

            if (correctIndex < 0 || correctIndex > 3) {
                throw new IllegalArgumentException(
                    "Line " + lineNum + ": correctIndex must be 0–3, got " + correctIndex);
            }
            if (timeLimitSec <= 0) {
                throw new IllegalArgumentException(
                    "Line " + lineNum + ": timeLimitSec must be a positive integer, got " + timeLimitSec);
            }

            questions.add(new Question(
                id, text,
                List.of(optA, optB, optC, optD),
                correctIndex,
                timeLimitSec
            ));
        }

        return questions;
    }

    private static void requireNonEmpty(String value, int lineNum, String fieldName) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                "Line " + lineNum + ": field '" + fieldName + "' must not be empty");
        }
    }

    private static int parseInt(String raw, int lineNum, String fieldName) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Line " + lineNum + ": field '" + fieldName
                + "' must be an integer, got \"" + raw + "\"");
        }
    }
}
