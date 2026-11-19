package quiz.common;

/** Owner: Member 2. A parsed protocol message (type + fields). */
public class Message {
    private final String type;
    private final String[] fields;

    public Message(String type, String... fields) {
        this.type = type;
        this.fields = fields;
    }

    public String getType() { return type; }
    public String[] getFields() { return fields; }

    // TODO: encode() -> "TYPE|f1|f2\n", static decode(String line)
}
