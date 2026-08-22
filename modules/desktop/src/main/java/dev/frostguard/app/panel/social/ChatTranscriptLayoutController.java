package dev.frostguard.app.panel.social;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.chat.ChatDiscordRenderer;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.engine.chat.ChatTranscriptStore;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * The live transcript: what the alliance and the world actually said, laid out the way a chat
 * client lays it out rather than as rows in a table.
 *
 * <p>This is drawn with ordinary JavaFX nodes rather than a browser view. Embedding one would have
 * allowed the Discord component libraries to be reused directly, but {@code javafx-web} carries
 * WebKit native libraries -- tens of megabytes per platform -- and this feature ships to people who
 * did not ask for it. Size is the constraint the whole design bends around, so the layout is
 * rebuilt with controls that are already on the classpath. {@link ChatDiscordRenderer} still
 * produces the browser version, which the export button writes out.
 */
public class ChatTranscriptLayoutController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Enough to scroll through a session without building a node for every message ever stored. */
    private static final int VIEW_LIMIT = 400;

    /** One stable colour per author, so a player is recognisable down the list. */
    private static final String[] AUTHOR_COLOURS = {
            "#5865f2", "#57f287", "#fee75c", "#eb459e", "#ed4245",
            "#3ba55d", "#faa81a", "#00b0f4", "#9b84ec", "#f47fff"
    };

    @FXML
    private VBox messageList;
    @FXML
    private Label statusLabel;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private ToggleButton tabAll;
    @FXML
    private ToggleButton tabWorld;
    @FXML
    private ToggleButton tabAlliance;
    @FXML
    private ToggleButton tabPersonal;
    @FXML
    private CheckBox checkHideChatter;

    /** Empty means every channel in one feed. */
    private String channelFilter = "";

    private final ChatTranscriptStore store =
            new ChatTranscriptStore(baseDir(), ZoneId.systemDefault());

    @FXML
    private void initialize() {
        // One channel at a time by default. World, Alliance and Personal are separate
        // conversations, and interleaving them is what made the transcript hard to follow.
        ToggleGroup channels = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {tabAll, tabWorld, tabAlliance, tabPersonal}) {
            t.setToggleGroup(channels);
        }
        // A toggle group lets the selected button be clicked off, which would leave no channel
        // chosen and an empty screen; re-select it instead.
        channels.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
        refresh();
    }

    @FXML
    private void selectChannel() {
        Toggle selected = tabAll.getToggleGroup().getSelectedToggle();
        Object data = selected == null ? null : ((ToggleButton) selected).getUserData();
        channelFilter = data == null ? "" : data.toString();
        refresh();
    }

    /**
     * Reloads the transcript off the UI thread.
     *
     * <p>The store reads whole day files, which grow all day, so doing it inline would stall the
     * interface for as long as the read takes on a machine that is also running an emulator.
     */
    @FXML
    private void refresh() {
        statusLabel.setText("Loading transcript...");
        Task<List<ChatMessage>> load = new Task<>() {
            @Override
            protected List<ChatMessage> call() throws IOException {
                // Read wider than the view limit before filtering, otherwise selecting one channel
                // shows only the handful of its messages that fell inside the last N overall.
                List<ChatMessage> all = store.recent(VIEW_LIMIT * 4);
                boolean hideChatter = checkHideChatter.isSelected();
                List<ChatMessage> kept = new java.util.ArrayList<>();
                for (ChatMessage m : all) {
                    if (!channelFilter.isEmpty() && !channelFilter.equals(m.channel())) {
                        continue;
                    }
                    if (hideChatter && (m.kind() == ChatMessage.Kind.SYSTEM
                            || dev.frostguard.api.chat.ChatLineCleaner.isNonSpeech(m.body()))) {
                        continue;
                    }
                    kept.add(m);
                }
                return kept.size() > VIEW_LIMIT
                        ? kept.subList(kept.size() - VIEW_LIMIT, kept.size())
                        : kept;
            }
        };
        load.setOnSucceeded(e -> Platform.runLater(() -> show(load.getValue())));
        load.setOnFailed(e -> Platform.runLater(() ->
                statusLabel.setText("Could not read the transcript: " + load.getException().getMessage())));
        Thread t = new Thread(load, "chat-transcript-load");
        t.setDaemon(true);
        t.start();
    }

    /** Writes the browser-rendered transcript beside the stored days. */
    @FXML
    private void exportHtml() {
        try {
            List<ChatMessage> messages = store.recent(VIEW_LIMIT);
            Path out = baseDir().resolve("transcript.html");
            Files.writeString(out, ChatDiscordRenderer.render(messages, ZoneId.systemDefault()),
                    StandardCharsets.UTF_8);
            statusLabel.setText("Exported " + messages.size() + " message(s) to " + out);
        } catch (IOException e) {
            statusLabel.setText("Export failed: " + e.getMessage());
        }
    }

    private void show(List<ChatMessage> messages) {
        messageList.getChildren().clear();

        if (messages.isEmpty()) {
            Label empty = new Label("No chat captured yet. Turn capture on in the Configure tab and "
                    + "the transcript fills in on the next pass.");
            empty.getStyleClass().add("label-muted");
            empty.setWrapText(true);
            messageList.getChildren().add(empty);
            statusLabel.setText(sizeLine(0));
            return;
        }

        String previousAuthor = null;
        String previousChannel = null;
        for (ChatMessage m : messages) {
            String author = m.author().isBlank() ? "unknown" : m.author();
            boolean grouped = author.equals(previousAuthor) && m.channel().equals(previousChannel);
            messageList.getChildren().add(grouped ? groupedRow(m) : leadRow(m, author));
            previousAuthor = author;
            previousChannel = m.channel();
        }

        statusLabel.setText(sizeLine(messages.size()));
        // A chat view is only useful pinned to the newest message; anything else means scrolling
        // past everything already read to find what arrived.
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    private String sizeLine(int shown) {
        try {
            return shown + " message(s) shown - transcript is "
                    + ChatTranscriptStore.humanSize(store.sizeBytes()) + " on disk";
        } catch (IOException e) {
            return shown + " message(s) shown";
        }
    }

    private HBox leadRow(ChatMessage m, String author) {
        HBox header = new HBox(8);
        header.setAlignment(Pos.BASELINE_LEFT);

        Label name = new Label(author);
        name.setStyle("-fx-font-weight: bold; -fx-text-fill: " + colourFor(author) + ";");
        header.getChildren().add(name);

        if (!m.allianceTag().isBlank()) {
            header.getChildren().add(chip(m.allianceTag(), "#4e5058", "#dbdee1"));
        }
        if (m.vipLevel() > 0) {
            header.getChildren().add(chip("VIP" + m.vipLevel(), "#faa81a", "#000000"));
        }

        Label channel = new Label(m.channel().toUpperCase(Locale.ROOT));
        channel.setStyle("-fx-font-size: 10px; -fx-text-fill: #949ba4;");
        Label time = new Label(TIME.format(m.capturedAt().atZone(ZoneId.systemDefault())));
        time.setStyle("-fx-font-size: 11px; -fx-text-fill: #949ba4;");
        header.getChildren().addAll(channel, time);

        VBox content = new VBox(2, header, bodyNode(m));
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox row = new HBox(10, avatar(author), content);
        row.setPadding(new Insets(6, 8, 2, 8));
        return row;
    }

    private HBox groupedRow(ChatMessage m) {
        Region spacer = new Region();
        spacer.setMinWidth(34);
        spacer.setPrefWidth(34);

        VBox content = new VBox(bodyNode(m));
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox row = new HBox(10, spacer, content);
        row.setPadding(new Insets(0, 8, 2, 8));
        return row;
    }

    /**
     * The message body, with the original kept under any translation.
     *
     * <p>A reader who speaks the language should not have to take the machine's word for it, and a
     * bad rendering is obvious when its source sits beside it.
     */
    private VBox bodyNode(ChatMessage m) {
        Text text = new Text(m.displayBody());
        switch (m.kind()) {
            case SYSTEM -> text.setStyle("-fx-fill: #949ba4; -fx-font-style: italic;");
            case EMOJI, STICKER -> text.setStyle("-fx-fill: #dbdee1; -fx-font-size: 18px;");
            default -> text.setStyle("-fx-fill: #dbdee1;");
        }

        TextFlow flow = new TextFlow(text);
        flow.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(1);

        // The quoted original goes above the reply, the way a chat client shows it, so it reads as
        // context rather than as part of what this player said.
        if (m.hasQuote()) {
            Text quote = new Text(m.quoted());
            quote.setStyle("-fx-fill: #949ba4; -fx-font-size: 11px;");
            TextFlow quoteFlow = new TextFlow(quote);
            quoteFlow.setStyle("-fx-border-color: transparent transparent transparent #4e5058;"
                    + " -fx-border-width: 0 0 0 2; -fx-padding: 0 0 0 6;");
            box.getChildren().add(quoteFlow);
        }
        box.getChildren().add(flow);

        if (!m.translated().isBlank()) {
            Text original = new Text(m.body());
            original.setStyle("-fx-fill: #949ba4; -fx-font-size: 11px;");
            box.getChildren().add(new TextFlow(original));
        }
        return box;
    }

    private StackPane avatar(String author) {
        Circle disc = new Circle(17);
        disc.setStyle("-fx-fill: " + colourFor(author) + ";");
        Label initial = new Label(author.isBlank() ? "?" : author.substring(0, 1).toUpperCase(Locale.ROOT));
        initial.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        StackPane pane = new StackPane(disc, initial);
        pane.setMinWidth(34);
        return pane;
    }

    private Label chip(String text, String background, String foreground) {
        Label chip = new Label(text);
        chip.setStyle("-fx-font-size: 10px; -fx-background-color: " + background
                + "; -fx-text-fill: " + foreground + "; -fx-background-radius: 3; -fx-padding: 1 5 1 5;");
        return chip;
    }

    private static String colourFor(String author) {
        int h = 0;
        for (int i = 0; i < author.length(); i++) {
            h = h * 31 + author.charAt(i);
        }
        return AUTHOR_COLOURS[Math.floorMod(h, AUTHOR_COLOURS.length)];
    }

    private static Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }
}
