package quiz.client.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class LoginScreen extends VBox {

    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField("localhost");
    private final TextField portField = new TextField("5050");

    private final Button joinButton = new Button("Join Game");
    private final Label statusLabel = new Label("Not connected");

    public LoginScreen() {

        setAlignment(Pos.CENTER);
        setSpacing(24);
        setPadding(new Insets(30));
        getStyleClass().add("app-background");

        Label brand = new Label("BRAINBRAWL");
        brand.getStyleClass().add("brand");

        Label subtitle = new Label("MULTIPLAYER QUIZ GAME");
        subtitle.getStyleClass().add("subtitle");

        Label title = new Label("Ready to Brawl?");
        title.getStyleClass().add("screen-title");

        Label description = new Label(
                "Think fast. Play smart. Win big."
        );
        description.getStyleClass().add("description");

        nameField.setPromptText("Enter your player name");

        VBox form = new VBox(10);

        form.getChildren().addAll(
                createLabel("Player Name"),
                nameField,
                createLabel("Server Address"),
                hostField,
                createLabel("Server Port"),
                portField
        );

        joinButton.setMaxWidth(Double.MAX_VALUE);
        joinButton.getStyleClass().add("primary-button");

        statusLabel.getStyleClass().add("status-label");

        VBox card = new VBox(18);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(30));
        card.setMaxWidth(420);
        card.getStyleClass().add("login-card");

        card.getChildren().addAll(
                title,
                description,
                form,
                joinButton,
                statusLabel
        );

        Label footer = new Label(
                "BrainBrawl | Java NIO Multiplayer Game"
        );
        footer.getStyleClass().add("footer");

        getChildren().addAll(
                brand,
                subtitle,
                card,
                footer
        );
    }

    private Label createLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    public String getPlayerName() {
        return nameField.getText().trim();
    }

    public String getHost() {
        return hostField.getText().trim();
    }

    public int getPort() {
        return Integer.parseInt(portField.getText().trim());
    }

    public Button getJoinButton() {
        return joinButton;
    }

    public void setStatus(String message) {
        statusLabel.setText(message);
    }

    public void setLoading(boolean loading) {
        joinButton.setDisable(loading);
        nameField.setDisable(loading);
        hostField.setDisable(loading);
        portField.setDisable(loading);

        joinButton.setText(
                loading ? "Connecting..." : "Join Game"
        );
    }
}