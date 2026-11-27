package quiz.client.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class LobbyScreen extends VBox {

    private final Label playerLabel = new Label();
    private final Label playerIdLabel = new Label();

    private final Label statusLabel =
            new Label("Waiting for the game to start...");

    private final Button leaveButton =
            new Button("Leave Game");

    public LobbyScreen(String playerName, String playerId) {

        setAlignment(Pos.CENTER);
        setSpacing(24);
        setPadding(new Insets(30));
        getStyleClass().add("app-background");

        Label brand = new Label("BRAINBRAWL");
        brand.getStyleClass().add("brand");

        Label connected = new Label("Connected");
        connected.getStyleClass().add("connected-label");

        Label title = new Label("Waiting Lobby");
        title.getStyleClass().add("screen-title");

        Label description = new Label(
                "Get ready for the next battle!"
        );
        description.getStyleClass().add("description");

        playerLabel.setText(playerName);
        playerLabel.getStyleClass().add("player-name");

        playerIdLabel.setText("Player ID: " + playerId);
        playerIdLabel.getStyleClass().add("description");

        Label ready = new Label("Ready");
        ready.getStyleClass().add("connected-label");

        VBox profile = new VBox(
                8, playerLabel, playerIdLabel, ready
        );

        profile.setPadding(new Insets(20));
        profile.setMaxWidth(Double.MAX_VALUE);
        profile.getStyleClass().add("player-card");

        ProgressIndicator loading = new ProgressIndicator();
        loading.setPrefSize(40, 40);
        loading.setMaxSize(40, 40);

        statusLabel.getStyleClass().add("description");

        leaveButton.setMaxWidth(Double.MAX_VALUE);
        leaveButton.getStyleClass().add("danger-button");

        VBox card = new VBox(
                20,
                title,
                description,
                profile,
                loading,
                statusLabel,
                leaveButton
        );

        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(30));
        card.setMaxWidth(450);
        card.getStyleClass().add("login-card");

        getChildren().addAll(
                brand,
                connected,
                card
        );
    }

    public Button getLeaveButton() {
        return leaveButton;
    }

    public void setStatus(String message) {
        statusLabel.setText(message);
    }
}