package com.rakesh.chat.client.ui;

import com.rakesh.chat.client.ChatClient;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The chat window. Everything JavaFX lives in this package; {@link ChatClient} knows
 * nothing about it.
 *
 * <p><b>The one rule to remember.</b> JavaFX has a single thread that owns every control on
 * screen. Our socket reader is a different thread. So every message that arrives from the
 * server takes a detour through {@link Platform#runLater} before it is allowed to touch the
 * screen, and every slow network call takes a detour onto a background thread before it is
 * allowed to run.
 *
 * <p>Layout is a BorderPane: connection bar on top, conversation in the middle, who-is-online
 * on the right, type-here box at the bottom.
 */
public class ChatWindow extends Application {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ChatClient client = new ChatClient();

    // An ObservableList tells the ListView when it changes, so we never have to ask the
    // ListView to redraw. Add to the list, the screen updates. That is the whole trick.
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<String> users = FXCollections.observableArrayList();

    private TextField hostField;
    private TextField portField;
    private TextField nickField;
    private TextField inputField;

    /** Phase 8. A PasswordField, not a TextField, so it shows dots instead of the secret. */
    private PasswordField passField;

    private Button connectButton;
    private Button disconnectButton;
    private Button sendButton;

    private Label statusLabel;
    private ListView<Message> messageList;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("chat-root");
        root.setTop(buildTopBar());
        root.setCenter(buildMessageList());
        root.setRight(buildUserList());
        root.setBottom(buildInputBar());

        // The reader thread calls these two. Both immediately hop to the FX thread.
        client.setListener(this::onServerMessage);
        client.setOnClosed(this::onConnectionClosed);

        Scene scene = new Scene(root, 780, 520);
        scene.getStylesheets().add(getClass().getResource("/chat.css").toExternalForm());

        stage.setTitle("Rakesh Chat");
        stage.setScene(scene);
        stage.show();

        showConnected(false);
        setStatus("not connected");
    }

    /**
     * Called by JavaFX when the last window closes. Closing the socket here is what makes
     * the program actually exit instead of lingering in Task Manager.
     */
    @Override
    public void stop() {
        client.disconnect();
    }

    // ------------------------------------------------------------------ building the screen

    private HBox buildTopBar() {
        hostField = new TextField("localhost");
        hostField.setPrefColumnCount(9);

        portField = new TextField("5000");
        portField.setPrefColumnCount(5);

        nickField = new TextField();
        nickField.setPromptText("nickname");
        nickField.setPrefColumnCount(10);

        // Phase 8. Leave it empty for plain text; type the server's passphrase to encrypt.
        passField = new PasswordField();
        passField.setPromptText("passphrase");
        passField.setPrefColumnCount(10);

        connectButton = new Button("Connect");
        connectButton.setOnAction(event -> doConnect());

        disconnectButton = new Button("Disconnect");
        disconnectButton.setOnAction(event -> doDisconnect());

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        HBox bar = new HBox(8,
                new Label("Host"), hostField,
                new Label("Port"), portField,
                new Label("Name"), nickField,
                new Label("Key"), passField,
                connectButton, disconnectButton, statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private ListView<Message> buildMessageList() {
        messageList = new ListView<>(messages);
        messageList.getStyleClass().add("message-list");
        messageList.setCellFactory(list -> new MessageCell());
        return messageList;
    }

    private ListView<String> buildUserList() {
        ListView<String> userList = new ListView<>(users);
        userList.getStyleClass().add("user-list");
        userList.setPrefWidth(150);

        // Double-click a name to start a private message to them.
        userList.setOnMouseClicked(event -> {
            String picked = userList.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && picked != null) {
                inputField.setText("/pm " + picked + " ");
                inputField.requestFocus();
                inputField.end();
            }
        });
        return userList;
    }

    private HBox buildInputBar() {
        inputField = new TextField();
        inputField.setPromptText("message, or /pm <name> <text>, /reply <text>, /list, /quit");
        inputField.setOnAction(event -> handleInput()); // Enter key
        HBox.setHgrow(inputField, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.setOnAction(event -> handleInput());

        HBox bar = new HBox(8, inputField, sendButton);
        bar.getStyleClass().add("input-bar");
        return bar;
    }

    // ------------------------------------------------------------------ connecting

    private void doConnect() {
        String host = hostField.getText().trim();
        String nickname = nickField.getText().trim();
        // Not trimmed: a space is a perfectly good character in a passphrase, and silently
        // removing one would produce a different key and a very confusing bug.
        String passphrase = passField.getText();

        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            setStatus("port must be a number");
            return;
        }
        if (nickname.isEmpty()) {
            setStatus("pick a nickname first");
            return;
        }

        connectButton.setDisable(true);
        setStatus("connecting...");

        // connect() waits for the network, so it must not run here - this method is on the
        // FX thread, and anything slow on the FX thread freezes the whole window. Phase 8
        // adds a second slow thing to keep off this thread: deriving the key.
        Thread connector = new Thread(() -> {
            try {
                client.connect(host, port, nickname, passphrase);
                Platform.runLater(() -> showConnected(true));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    showConnected(false);
                    setStatus("could not connect: " + e.getMessage());
                });
            }
        }, "chat-client-connect");
        connector.setDaemon(true);
        connector.start();
    }

    private void doDisconnect() {
        client.disconnect();
        showConnected(false);
        users.clear();
        setStatus("disconnected");
    }

    // ------------------------------------------------------------------ messages in

    /**
     * Every message from the server arrives here, <b>on the reader thread</b>. Not one line
     * below may touch a control directly, so the whole body is handed to the FX thread.
     */
    private void onServerMessage(Message message) {
        Platform.runLater(() -> {
            switch (message.type()) {
                // The server already sends this list in order, so no sorting needed.
                case USERS -> users.setAll(message.userList());

                case WELCOME -> {
                    messages.add(message);
                    setStatus("connected as " + message.sender()
                            + (client.isEncrypted() ? " (encrypted)" : " (plain text)"));
                    client.send(Message.list()); // ask who else is here
                }

                case JOINED -> {
                    messages.add(message);
                    if (!users.contains(message.sender())) {
                        users.add(message.sender());
                        FXCollections.sort(users, String.CASE_INSENSITIVE_ORDER);
                    }
                }

                case LEFT -> {
                    messages.add(message);
                    users.remove(message.sender());
                }

                default -> messages.add(message);
            }
            messageList.scrollTo(messages.size() - 1);
        });
    }

    /** The connection dropped on its own. Also on the reader thread. */
    private void onConnectionClosed(String reason) {
        Platform.runLater(() -> {
            showConnected(false);
            users.clear();
            setStatus(reason);
        });
    }

    // ------------------------------------------------------------------ messages out

    private void handleInput() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!client.isConnected()) {
            setStatus("not connected");
            return;
        }

        Message message;
        try {
            message = toMessage(text);
        } catch (IllegalArgumentException e) {
            // Message's own validation rejected it, e.g. a nickname with a comma in it.
            setStatus("cannot send that: " + e.getMessage());
            return;
        }
        if (message == null) {
            return; // toMessage already explained why
        }

        client.send(message);
        inputField.clear();

        if (message.type() == MessageType.MSG) {
            // The server does not send your own room message back to you, so show it here.
            // Private messages need no such trick: the server confirms them with SENT.
            messages.add(message);
            messageList.scrollTo(messages.size() - 1);
        }
        if (message.type() == MessageType.QUIT) {
            doDisconnect();
        }
    }

    /** Turns what the user typed into a protocol message, or null if it was not usable. */
    private Message toMessage(String text) {
        if (text.startsWith("/pm ")) {
            String rest = text.substring(4).trim();
            int space = rest.indexOf(' ');
            if (space < 0) {
                setStatus("usage: /pm <name> <message>");
                return null;
            }
            return Message.pm(rest.substring(0, space), rest.substring(space + 1));
        }
        if (text.startsWith("/reply ")) {
            return Message.reply(text.substring(7).trim());
        }
        if (text.equals("/list")) {
            return Message.list();
        }
        if (text.equals("/quit")) {
            return Message.quit();
        }
        if (text.startsWith("/")) {
            setStatus("unknown command: " + text);
            return null;
        }
        return Message.msg(text);
    }

    // ------------------------------------------------------------------ small helpers

    private void showConnected(boolean online) {
        connectButton.setDisable(online);
        disconnectButton.setDisable(!online);
        hostField.setDisable(online);
        portField.setDisable(online);
        nickField.setDisable(online);
        passField.setDisable(online);
        inputField.setDisable(!online);
        sendButton.setDisable(!online);

        statusLabel.getStyleClass().removeAll("status-online", "status-offline");
        statusLabel.getStyleClass().add(online ? "status-online" : "status-offline");
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ------------------------------------------------------------------ how a row looks

    /**
     * Draws one row of the conversation.
     *
     * <p><b>The trap here.</b> A ListView only builds as many cells as fit on screen and
     * then reuses them as you scroll, so the same cell object shows a whisper now and a
     * system notice a second later. That is why {@code updateItem} clears the style classes
     * every single time — forget that line and old colours smear down the list.
     */
    private static class MessageCell extends ListCell<Message> {

        @Override
        protected void updateItem(Message message, boolean empty) {
            super.updateItem(message, empty);

            getStyleClass().removeAll("own-message", "chat-message",
                    "whisper-message", "sent-message", "system-message");

            if (empty || message == null) {
                setText(null);
                return;
            }

            setText(describe(message));

            switch (message.type()) {
                case MSG -> {
                    getStyleClass().add("own-message");
                    setAlignment(Pos.CENTER_RIGHT);
                }
                case SENT -> {
                    getStyleClass().add("sent-message");
                    setAlignment(Pos.CENTER_RIGHT);
                }
                case CHAT -> {
                    getStyleClass().add("chat-message");
                    setAlignment(Pos.CENTER_LEFT);
                }
                case WHISPER -> {
                    getStyleClass().add("whisper-message");
                    setAlignment(Pos.CENTER_LEFT);
                }
                default -> {
                    getStyleClass().add("system-message");
                    setAlignment(Pos.CENTER_LEFT);
                }
            }
        }

        private static String describe(Message m) {
            return switch (m.type()) {
                case MSG     -> "you: " + m.body();
                case CHAT    -> time(m) + m.sender() + ": " + m.body();
                case WHISPER -> time(m) + "[private] " + m.sender() + ": " + m.body();
                case SENT    -> time(m) + "[private to " + m.target() + "] " + m.body();
                case WELCOME -> "* joined " + m.body() + " as " + m.sender();
                case JOINED  -> "* " + m.sender() + " joined";
                case LEFT    -> "* " + m.sender() + " left";
                case ERROR   -> "! " + m.target() + ": " + m.body();
                default      -> m.serialize();
            };
        }

        private static String time(Message m) {
            return (m.timestamp() == null) ? "" : CLOCK.format(m.timestamp()) + "  ";
        }
    }
}
