package quiz.game;

/** Owner: Member 3. One multiple-choice question. */
public class Question {
    private final int id;
    private final String text;
    private final String[] options;   // A, B, C, D
    private final int correctIndex;

    public Question(int id, String text, String[] options, int correctIndex) {
        this.id = id;
        this.text = text;
        this.options = options;
        this.correctIndex = correctIndex;
    }

    public int getId() { return id; }
    public String getText() { return text; }
    public String[] getOptions() { return options; }
    public int getCorrectIndex() { return correctIndex; }
}
