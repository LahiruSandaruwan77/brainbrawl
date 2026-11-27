package quiz.client.gui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.function.Consumer;

public class QuizScreen extends VBox {

    private final Label questionNumber = new Label();
    private final Label questionText = new Label();
    private final Label timerLabel = new Label();
    private final Label scoreLabel = new Label("Score: 0");
    private final Label statusLabel = new Label();

    private final ProgressBar timeBar = new ProgressBar(1);

    private final ToggleGroup answers = new ToggleGroup();
    private final RadioButton[] options = new RadioButton[4];

    private final Button submitButton =
            new Button("Submit Answer");

    private Timeline timer;
    private int remainingSeconds;
    private String questionId;
    private boolean answered;

    private Consumer<String> answerHandler;

    public QuizScreen() {

        setAlignment(Pos.CENTER);
        setSpacing(20);
        setPadding(new Insets(30));
        getStyleClass().add("app-background");

        Label brand = new Label("BRAINBRAWL");
        brand.getStyleClass().add("brand");

        questionNumber.getStyleClass().add("description");
        timerLabel.getStyleClass().add("timer-label");
        scoreLabel.getStyleClass().add("connected-label");

        HBox header = new HBox(
                40, questionNumber, timerLabel, scoreLabel
        );

        header.setAlignment(Pos.CENTER);

        timeBar.setMaxWidth(550);

        questionText.setWrapText(true);
        questionText.getStyleClass().add("question-text");

        VBox answerBox = new VBox(12);

        for (int i = 0; i < 4; i++) {

            RadioButton option = new RadioButton();

            option.setToggleGroup(answers);
            option.setUserData(String.valueOf((char) ('A' + i)));
            option.setMaxWidth(Double.MAX_VALUE);
            option.setWrapText(true);
            option.getStyleClass().add("answer-option");

            options[i] = option;

            answerBox.getChildren().add(option);
        }

        submitButton.setMaxWidth(Double.MAX_VALUE);
        submitButton.getStyleClass().add("primary-button");
        submitButton.setDisable(true);

        statusLabel.getStyleClass().add("description");

        answers.selectedToggleProperty().addListener(
                (observable, oldValue, newValue) -> {

                    submitButton.setDisable(
                            newValue == null || answered
                    );
                }
        );

        submitButton.setOnAction(event -> submitAnswer());

        VBox card = new VBox(
                20,
                questionText,
                answerBox,
                submitButton,
                statusLabel
        );

        card.setPadding(new Insets(25));
        card.setMaxWidth(550);
        card.getStyleClass().add("login-card");

        getChildren().addAll(
                brand,
                header,
                timeBar,
                card
        );
    }

    public void showQuestion(
            String id,
            String text,
            String[] choices,
            int seconds) {

        stopTimer();

        questionId = id;
        answered = false;

        questionNumber.setText("Question: " + id);
        questionText.setText(text);

        answers.selectToggle(null);

        for (int i = 0; i < 4; i++) {

            options[i].setText(
                    (char) ('A' + i) + ". " + choices[i]
            );

            options[i].setDisable(false);
        }

        submitButton.setDisable(true);

        statusLabel.setText(
                "Select one answer before time runs out."
        );

        startTimer(seconds);
    }

    private void startTimer(int seconds) {

        int totalSeconds = Math.max(1, seconds);

        remainingSeconds = totalSeconds;

        timerLabel.setText(remainingSeconds + "s");
        timeBar.setProgress(1);

        timer = new Timeline(
                new KeyFrame(Duration.seconds(1), event -> {

                    remainingSeconds--;

                    timerLabel.setText(remainingSeconds + "s");

                    timeBar.setProgress(
                            (double) remainingSeconds / totalSeconds
                    );

                    if (remainingSeconds <= 0) {

                        stopTimer();
                        lockAnswers();

                        statusLabel.setText(
                                "Time is up! Waiting for result..."
                        );
                    }
                })
        );

        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void submitAnswer() {

        Toggle selected = answers.getSelectedToggle();

        if (selected == null || answered) {
            return;
        }

        if (answerHandler == null) {
            statusLabel.setText("Connection not available.");
            return;
        }

        String choice = selected.getUserData().toString();

        lockAnswers();
        stopTimer();

        statusLabel.setText(
                "Answer submitted. Waiting for result..."
        );

        answerHandler.accept(
                "ANSWER|" + questionId + "|" + choice
        );
    }

    private void lockAnswers() {

        answered = true;

        submitButton.setDisable(true);

        for (RadioButton option : options) {
            option.setDisable(true);
        }
    }

    public void showResult(String correctChoice) {

        stopTimer();
        lockAnswers();

        Toggle selected = answers.getSelectedToggle();

        boolean correct = selected != null &&
                correctChoice.equals(
                        selected.getUserData().toString()
                );

        statusLabel.setText(
                correct
                        ? "Correct answer!"
                        : "Correct answer: " + correctChoice
        );
    }

    public void setScore(int score) {
        scoreLabel.setText("Score: " + score);
    }

    public void setAnswerHandler(
            Consumer<String> handler) {

        answerHandler = handler;
    }

    public void stopTimer() {

        if (timer != null) {
            timer.stop();
        }
    }
}