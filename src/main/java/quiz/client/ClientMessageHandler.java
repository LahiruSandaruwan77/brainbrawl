package quiz.client;

import java.util.function.Consumer;

public class ClientMessageHandler {

    private final Consumer<String[]> onWelcome;
    private final Consumer<String[]> onQuestion;
    private final Consumer<String[]> onResult;
    private final Consumer<String[]> onScores;
    private final Consumer<String[]> onEnd;
    private final Consumer<String> onError;

    public ClientMessageHandler(
            Consumer<String[]> onWelcome,
            Consumer<String[]> onQuestion,
            Consumer<String[]> onResult,
            Consumer<String[]> onScores,
            Consumer<String[]> onEnd,
            Consumer<String> onError) {

        this.onWelcome = onWelcome;
        this.onQuestion = onQuestion;
        this.onResult = onResult;
        this.onScores = onScores;
        this.onEnd = onEnd;
        this.onError = onError;
    }

    public void handle(String message) {

        if (message == null || message.isBlank()) {
            return;
        }

        String[] parts = message.split("\\|", -1);

        switch (parts[0]) {

            case "WELCOME":
                dispatch(parts, 2, onWelcome);
                break;

            case "QUESTION":
                dispatch(parts, 8, onQuestion);
                break;

            case "RESULT":
                dispatch(parts, 3, onResult);
                break;

            case "SCORES":
                dispatch(parts, 2, onScores);
                break;

            case "END":
                dispatch(parts, 2, onEnd);
                break;

            case "ERROR":
                onError.accept(
                    parts.length > 1
                        ? parts[1]
                        : "Server error"
                );
                break;

            default:
                onError.accept(
                    "Unknown message: " + message
                );
        }
    }

    private void dispatch(
            String[] parts,
            int expectedLength,
            Consumer<String[]> handler) {

        if (parts.length != expectedLength) {
            onError.accept(
                "Invalid message format: " + parts[0]
            );
            return;
        }

        handler.accept(parts);
    }
}