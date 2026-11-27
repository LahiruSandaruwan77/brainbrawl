package quiz.client.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import quiz.client.ClientConnection;
import quiz.client.ClientMessageHandler;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrainBrawlApp extends Application {

    private Scene scene;

    private LoginScreen loginScreen;
    private LobbyScreen lobbyScreen;
    private QuizScreen quizScreen;
    private LeaderboardScreen leaderboardScreen;

    private ClientConnection connection;

    private String playerName;
    private String currentQuestionId;

    private int playerScore;
    private boolean gameEnded;

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(
                        task, "brainbrawl-network-worker"
                );
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void start(Stage stage) {

        loginScreen = new LoginScreen();

        scene = new Scene(loginScreen, 800, 700);

        URL stylesheet = getClass().getResource(
                "/client/styles.css"
        );

        if (stylesheet != null) {
            scene.getStylesheets().add(
                    stylesheet.toExternalForm()
            );
        }

        loginScreen.getJoinButton().setOnAction(
                event -> joinGame()
        );

        stage.setTitle("BrainBrawl - Multiplayer Quiz");
        stage.setScene(scene);
        stage.setMinWidth(600);
        stage.setMinHeight(680);
        stage.show();
    }

    private void joinGame() {

        String name = loginScreen.getPlayerName().trim();
        String host = loginScreen.getHost().trim();

        if (name.isBlank()) {
            loginScreen.setStatus(
                    "Please enter your player name."
            );
            return;
        }

        if (name.matches("(?s).*[|,:\\r\\n].*")) {
            loginScreen.setStatus(
                    "Player name contains invalid characters."
            );
            return;
        }

        if (host.isBlank()) {
            loginScreen.setStatus(
                    "Please enter the server address."
            );
            return;
        }

        int port;

        try {

            port = loginScreen.getPort();

            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }

        } catch (NumberFormatException e) {

            loginScreen.setStatus(
                    "Please enter a valid port number."
            );
            return;
        }

        playerName = name;
        playerScore = 0;
        gameEnded = false;
        currentQuestionId = null;

        quizScreen = new QuizScreen();
        leaderboardScreen = new LeaderboardScreen();

        leaderboardScreen.getHomeButton().setOnAction(
                event -> returnHome("Ready to join a new game.")
        );

        quizScreen.setAnswerHandler(this::sendMessage);

        loginScreen.setLoading(true);
        loginScreen.setStatus("Connecting to server...");

        ClientMessageHandler handler =
                new ClientMessageHandler(
                        this::handleWelcome,
                        this::handleQuestion,
                        this::handleResult,
                        this::handleScores,
                        this::handleEnd,
                        this::handleError
                );

        ClientConnection[] session = new ClientConnection[1];

        ClientConnection client = new ClientConnection(

                message -> Platform.runLater(() -> {

                    if (connection == session[0]) {
                        handler.handle(message);
                    }
                }),

                reason -> Platform.runLater(() -> {

                    if (connection == session[0] && !gameEnded) {
                        returnHome(reason);
                    }
                })
        );

        session[0] = client;
        connection = client;

        networkExecutor.execute(() -> {

            try {

                client.connect(host, port);
                client.send("JOIN|" + name);

            } catch (Exception e) {

                client.close();

                Platform.runLater(() -> {

                    if (connection == client) {
                        returnHome(
                                "Connection failed: " + e.getMessage()
                        );
                    }
                });
            }
        });
    }

    private void handleWelcome(String[] parts) {

        String playerId = parts[1];

        loginScreen.setLoading(false);

        lobbyScreen = new LobbyScreen(
                playerName,
                playerId
        );

        lobbyScreen.getLeaveButton().setOnAction(
                event -> returnHome("You left the game.")
        );

        scene.setRoot(lobbyScreen);
    }

    private void handleQuestion(String[] parts) {

        if (gameEnded) {
            return;
        }

        try {

            String questionId = parts[1];
            String questionText = parts[2];

            String[] choices = {
                    parts[3],
                    parts[4],
                    parts[5],
                    parts[6]
            };

            int seconds = Integer.parseInt(parts[7]);

            if (seconds <= 0) {
                throw new NumberFormatException();
            }

            currentQuestionId = questionId;

            quizScreen.setScore(playerScore);

            quizScreen.showQuestion(
                    questionId,
                    questionText,
                    choices,
                    seconds
            );

            scene.setRoot(quizScreen);

        } catch (NumberFormatException e) {

            handleError("Invalid question timer.");
        }
    }

    private void handleResult(String[] parts) {

        String questionId = parts[1];
        String correctChoice = parts[2];

        if (!gameEnded &&
                questionId.equals(currentQuestionId)) {

            quizScreen.showResult(correctChoice);
        }
    }

    private void handleScores(String[] parts) {

        String scoreData = parts[1];

        leaderboardScreen.updateScores(scoreData);

        for (String entry : scoreData.split(",")) {

            String[] scoreParts = entry.split(":", -1);

            if (scoreParts.length == 2 &&
                    scoreParts[0].trim().equals(playerName)) {

                try {

                    playerScore = Integer.parseInt(
                            scoreParts[1].trim()
                    );

                    quizScreen.setScore(playerScore);

                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void handleEnd(String[] parts) {

        gameEnded = true;

        quizScreen.stopTimer();

        leaderboardScreen.showWinner(parts[1]);

        scene.setRoot(leaderboardScreen);

        closeConnection();
    }

    private void handleError(String message) {

        if (scene.getRoot() == loginScreen) {

            returnHome(message);

        } else {

            Alert alert = new Alert(
                    Alert.AlertType.WARNING
            );

            alert.setTitle("BrainBrawl");
            alert.setHeaderText("Server Message");
            alert.setContentText(message);
            alert.show();
        }
    }

    private void sendMessage(String message) {

        ClientConnection client = connection;

        if (client == null) {
            handleError("Not connected to server.");
            return;
        }

        networkExecutor.execute(() -> {

            try {

                client.send(message);

            } catch (Exception e) {

                Platform.runLater(() -> {

                    if (connection == client) {
                        returnHome(
                                "Unable to send message: "
                                        + e.getMessage()
                        );
                    }
                });
            }
        });
    }

    private void returnHome(String message) {

        closeConnection();

        if (quizScreen != null) {
            quizScreen.stopTimer();
        }

        loginScreen.setLoading(false);
        loginScreen.setStatus(message);

        scene.setRoot(loginScreen);
    }

    private void closeConnection() {

        ClientConnection client = connection;
        connection = null;

        if (client != null) {
            client.close();
        }
    }

    @Override
    public void stop() {

        if (quizScreen != null) {
            quizScreen.stopTimer();
        }

        closeConnection();

        networkExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}