package quiz.client.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LeaderboardScreen extends VBox {

    private final VBox rankings = new VBox(10);

    private final Label winnerLabel = new Label();

    private final Label statusLabel =
            new Label("Live player rankings");

    private final Button homeButton =
            new Button("Back to Home");

    private record PlayerScore(String name, int score) {}

    public LeaderboardScreen() {

        setAlignment(Pos.CENTER);
        setSpacing(20);
        setPadding(new Insets(30));

        getStyleClass().add("app-background");

        Label brand = new Label("BRAINBRAWL");
        brand.getStyleClass().add("brand");

        Label trophy = new Label("🏆");
        trophy.getStyleClass().add("trophy-icon");

        Label title = new Label("Leaderboard");
        title.getStyleClass().add("screen-title");

        statusLabel.getStyleClass().add("description");

        Label rankHeader = new Label("RANK");
        Label playerHeader = new Label("PLAYER");
        Label scoreHeader = new Label("SCORE");

        rankHeader.getStyleClass().add("description");
        playerHeader.getStyleClass().add("description");
        scoreHeader.getStyleClass().add("description");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(
                20,
                rankHeader,
                playerHeader,
                spacer,
                scoreHeader
        );

        header.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scrollPane = new ScrollPane(rankings);

        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(280);
        scrollPane.getStyleClass().add("ranking-scroll");

        winnerLabel.getStyleClass().add("winner-label");
        winnerLabel.setWrapText(true);
        winnerLabel.setVisible(false);
        winnerLabel.setManaged(false);

        homeButton.getStyleClass().add("primary-button");
        homeButton.setMaxWidth(Double.MAX_VALUE);
        homeButton.setVisible(false);
        homeButton.setManaged(false);

        VBox card = new VBox(
                15,
                header,
                scrollPane,
                winnerLabel,
                homeButton
        );

        card.setPadding(new Insets(25));
        card.setMaxWidth(550);
        card.getStyleClass().add("login-card");

        getChildren().addAll(
                brand,
                trophy,
                title,
                statusLabel,
                card
        );
    }

    public void updateScores(String scoreData) {

        List<PlayerScore> players = new ArrayList<>();

        if (scoreData != null && !scoreData.isBlank()) {

            for (String entry : scoreData.split(",")) {

                String[] parts = entry.split(":", -1);

                if (parts.length != 2) {
                    continue;
                }

                try {

                    players.add(new PlayerScore(
                            parts[0].trim(),
                            Integer.parseInt(parts[1].trim())
                    ));

                } catch (NumberFormatException ignored) {
                    // Skip invalid score entries.
                }
            }
        }

        players.sort(
                Comparator.comparingInt(PlayerScore::score)
                        .reversed()
        );

        rankings.getChildren().clear();

        int rank = 0;
        Integer previousScore = null;

        for (int i = 0; i < players.size(); i++) {

            PlayerScore player = players.get(i);

            if (previousScore == null ||
                    player.score() != previousScore) {

                rank = i + 1;
            }

            rankings.getChildren().add(
                    createRankingRow(rank, player)
            );

            previousScore = player.score();
        }
    }

    private HBox createRankingRow(
            int rank,
            PlayerScore player) {

        String rankText = switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> String.valueOf(rank);
        };

        Label rankLabel = new Label(rankText);
        rankLabel.setMinWidth(40);
        rankLabel.getStyleClass().add("ranking-position");

        Label nameLabel = new Label(player.name());
        nameLabel.getStyleClass().add("ranking-name");

        Label scoreLabel =
                new Label(player.score() + " pts");

        scoreLabel.getStyleClass().add("ranking-score");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(
                15,
                rankLabel,
                nameLabel,
                spacer,
                scoreLabel
        );

        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14));

        row.getStyleClass().add("ranking-row");

        if (rank == 1) {
            row.getStyleClass().add("first-place");
        }

        return row;
    }

    public void showWinner(String winner) {

        statusLabel.setText("Game Over!");

        winnerLabel.setText("🏆 Winner: " + winner);

        winnerLabel.setVisible(true);
        winnerLabel.setManaged(true);

        homeButton.setVisible(true);
        homeButton.setManaged(true);
    }

    public Button getHomeButton() {
        return homeButton;
    }
}