package quiz.common;

/** Owner: Member 2. Protocol constants and newline framing helpers. */
public final class Protocol {
    public static final int PORT = 5050;
    public static final char DELIMITER = '|';
    public static final char TERMINATOR = '\n';

    public static final String JOIN = "JOIN";
    public static final String WELCOME = "WELCOME";
    public static final String QUESTION = "QUESTION";
    public static final String ANSWER = "ANSWER";
    public static final String RESULT = "RESULT";
    public static final String SCORES = "SCORES";
    public static final String END = "END";

    private Protocol() {}

    // TODO: ByteBuffer-based frame reader (flip/compact, handle partial reads)
}
