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
        name.getStyleClass().add("chat-author");
        // The colour is per-author and computed, so it stays in Java; everything else about the
        // name lives in the stylesheet.
        name.setStyle("-fx-text-fill: " + colourFor(author) + ";");
        header.getChildren().add(name);

        if (!m.allianceTag().isBlank()) {
            header.getChildren().add(chip(m.allianceTag()));
        }

        Label channel = new Label(m.channel().toUpperCase(Locale.ROOT));
        channel.getStyleClass().add("chat-channel");
        Label time = new Label(TIME.format(m.capturedAt().atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("chat-time");
        header.getChildren().addAll(channel, time);

        VBox content = new VBox(2, header, bodyNode(m));
        content.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);

        // No avatar disc. A coloured circle carrying one letter of a name that is already written
        // in full on the next line adds nothing to read.
        HBox row = new HBox(10, content);
        // Without this the row is only ever as wide as the text it happens to hold. A box's
        // maximum defaults to its computed size, so it never grows into the space beside it, and
        // the message wraps into a narrow column with the rest of the panel left empty.
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("chat-row");
        return row;
    }

    private HBox groupedRow(ChatMessage m) {
        // Grouped messages used to indent past the avatar column; with no avatar there is nothing
        // to clear, so they line up with the message above them.
        Region spacer = new Region();
        spacer.setMinWidth(0);
        spacer.setPrefWidth(0);

        VBox content = new VBox(bodyNode(m));
        content.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox row = new HBox(10, spacer, content);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("chat-row-grouped");
        return row;
    }

    /**
     * The message body: the quote it answers, what was said, and the source under a translation.
     *
     * <p>A reader who speaks the language should not have to take the machine's word for it, and a
     * bad rendering is obvious when its source sits beside it.
     */
    private VBox bodyNode(ChatMessage m) {
        VBox box = new VBox(3);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);

        // The quote goes above the reply, the way a chat client shows it, so it reads as context
        // rather than as part of what this player said. Behind an accent bar and dimmer, because
        // a reader scanning the feed needs to skip it as easily as read it.
        if (m.hasQuote()) {
            box.getChildren().add(quoteNode(m.quoted()));
        }

        TextFlow flow = withMentions(m.displayBody(), bodyClass(m));
        flow.setMaxWidth(Double.MAX_VALUE);

        // A long message given a ground of its own reads as one object that can be stopped at or
        // skipped. Set as loose text it swallows the messages either side of it, which is what an
        // alliance call running to a hundred words was doing to the whole afternoon around it.
        if (m.displayBody().length() >= BLOCK_LENGTH) {
            VBox block = new VBox(flow);
            block.getStyleClass().add("chat-block");
            block.setMaxWidth(Double.MAX_VALUE);
            block.setFillWidth(true);
            box.getChildren().add(block);
        } else {
            box.getChildren().add(flow);
        }

        if (!m.translated().isBlank()) {
            Text original = new Text(m.body());
            original.getStyleClass().add("chat-original");
            TextFlow originalFlow = new TextFlow(original);
            originalFlow.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(originalFlow);
        }
        return box;
    }

    /** Past this a message is a block rather than a line, and is given its own ground. */
    private static final int BLOCK_LENGTH = 180;

    private static String bodyClass(ChatMessage m) {
        return switch (m.kind()) {
            case SYSTEM -> "chat-body-system";
            case EMOJI, STICKER -> "chat-body-emoji";
            default -> "chat-body";
        };
    }

    /**
     * The message being replied to, with the name it belongs to picked out.
     *
     * <p>The game writes a quote as "Name: what they said". Setting the name apart lets a reader
     * tell a reply to one person from a reply to another without reading the strip.
     */
    private VBox quoteNode(String quoted) {
        int colon = quoted.indexOf(':');
        TextFlow flow = new TextFlow();
        if (colon > 0 && colon < MAX_QUOTED_NAME) {
            Text who = new Text(quoted.substring(0, colon + 1) + " ");
            who.getStyleClass().add("chat-quote-who");
            flow.getChildren().add(who);
            flow.getChildren().addAll(mentionParts(quoted.substring(colon + 1).trim(),
                    "chat-quote-text"));
        } else {
            flow.getChildren().addAll(mentionParts(quoted, "chat-quote-text"));
        }
        flow.setMaxWidth(Double.MAX_VALUE);
        VBox wrap = new VBox(flow);
        wrap.getStyleClass().add("chat-quote");
        wrap.setMaxWidth(Double.MAX_VALUE);
        wrap.setFillWidth(true);
        return wrap;
    }

    /** Longer than this before a colon and it is a sentence, not a name. */
    private static final int MAX_QUOTED_NAME = 24;

    /** A line of text with the people in it picked out from the words around them. */
    private TextFlow withMentions(String body, String bodyStyle) {
        return new TextFlow(mentionParts(body, bodyStyle).toArray(Text[]::new));
    }

    /**
     * Splits a line into ordinary words and the people named in it.
     *
     * <p>A mention is what makes a feed a conversation rather than a list -- it says who is talking
     * to whom -- and it is invisible when set in the same colour as everything else. Addressing the
     * whole alliance gets its own colour because the game draws it as its own thing, and a
     * transcript that flattens the two loses the difference between a broadcast and a reply.
     */
    private java.util.List<Text> mentionParts(String body, String bodyStyle) {
        java.util.List<Text> parts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = MENTION.matcher(body);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                Text plain = new Text(body.substring(at, m.start()));
                plain.getStyleClass().add(bodyStyle);
                parts.add(plain);
            }
            Text mention = new Text(m.group());
            mention.getStyleClass().add(m.group().equalsIgnoreCase("@All")
                    ? "chat-mention-all" : "chat-mention");
            parts.add(mention);
            at = m.end();
        }
        if (at < body.length()) {
            Text tail = new Text(body.substring(at));
            tail.getStyleClass().add(bodyStyle);
            parts.add(tail);
        }
        if (parts.isEmpty()) {
            Text only = new Text(body);
            only.getStyleClass().add(bodyStyle);
            parts.add(only);
        }
        return parts;
    }

    /** A name the game wrote with an "@" in front of it, including the few that carry a space. */
    private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile(
            "@[A-Za-z0-9_.-]{2,16}(?: [A-Z][A-Za-z0-9_.-]{1,12})?");

    private Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chat-chip");
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
