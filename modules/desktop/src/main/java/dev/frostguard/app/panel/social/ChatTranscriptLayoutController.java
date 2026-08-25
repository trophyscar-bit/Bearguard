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

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
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

    /** Close enough to the bottom to count as reading the newest message. */
    private static final double NEWEST_ENOUGH = 0.98;

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
    private ToggleButton tabWorld;
    @FXML
    private ToggleButton tabAlliance;
    @FXML
    private ToggleButton tabPersonal;
    @FXML
    private CheckBox checkHideChatter;
    @FXML
    private ToggleButton tabReaderPython;
    @FXML
    private ToggleButton tabReaderJava;

    /** Which conversation is on screen. Alliance is what the panel opens on. */
    private String channelFilter = "alliance";

    /**
     * Which reader's transcript is being shown, and the store that reads it.
     *
     * <p>Rebuilt when the reader changes rather than held for both, because a store is bound to one
     * directory and the whole point of the switch is that the two directories hold different
     * answers to the same screens.
     */
    private String readerFilter = "SERVICE";

    private ChatTranscriptStore store = new ChatTranscriptStore(baseDir("SERVICE"),
            ZoneId.systemDefault());

    @FXML
    private void initialize() {
        // One channel at a time by default. World, Alliance and Personal are separate
        // conversations, and interleaving them is what made the transcript hard to follow.
        ToggleGroup readers = new ToggleGroup();
        tabReaderPython.setToggleGroup(readers);
        tabReaderJava.setToggleGroup(readers);
        readers.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });

        ToggleGroup channels = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {tabWorld, tabAlliance, tabPersonal}) {
            t.setToggleGroup(channels);
        }
        // A toggle group lets the selected button be clicked off, which would leave no channel
        // chosen and an empty screen; re-select it instead.
        channels.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
        // Capture runs on its own schedule, so a panel left open goes stale without saying so --
        // it shows a quiet afternoon that ended half an hour ago. Reloading on the same cadence as
        // the capture keeps the two in step.
        follow = new Timeline(new KeyFrame(Duration.minutes(REFRESH_MINUTES), e -> refresh()));
        follow.setCycleCount(Timeline.INDEFINITE);
        follow.play();

        refresh();
    }

    /** Reloads on the capture's own cadence, so an open panel does not quietly go stale. */
    private Timeline follow;

    private static final int REFRESH_MINUTES = 30;

    @FXML
    private void selectReader() {
        Toggle selected = tabReaderPython.getToggleGroup().getSelectedToggle();
        Object data = selected == null ? null : ((ToggleButton) selected).getUserData();
        readerFilter = data == null ? "SERVICE" : data.toString();
        store = new ChatTranscriptStore(baseDir(readerFilter), ZoneId.systemDefault());
        refresh();
    }

    @FXML
    private void selectChannel() {
        Toggle selected = tabWorld.getToggleGroup().getSelectedToggle();
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
            Path out = baseDir(readerFilter).resolve("transcript.html");
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

        for (ChatMessage m : messages) {
            String author = m.author().isBlank() ? "unknown" : m.author();
            messageList.getChildren().add(card(m, author));
        }

        statusLabel.setText(sizeLine(messages.size()));
        // Pinned to the newest message, unless the reader had scrolled back to look at something.
        // A view that reloads itself every half hour and yanks you to the bottom while you are
        // reading is worse than one that never reloads at all.
        double was = scrollPane.getVvalue();
        boolean wasAtNewest = was >= NEWEST_ENOUGH || messageList.getChildren().size() <= 1;
        Platform.runLater(() -> scrollPane.setVvalue(wasAtNewest ? 1.0 : was));
    }

    private String sizeLine(int shown) {
        try {
            return shown + " message(s) shown - transcript is "
                    + ChatTranscriptStore.humanSize(store.sizeBytes()) + " on disk";
        } catch (IOException e) {
            return shown + " message(s) shown";
        }
    }

    /**
     * One message as a card, with a bar in the author's colour down its left edge.
     *
     * <p>Every message carries its own name and time. Consecutive messages from one person used to
     * share a single name, which saved a little height and cost the thing the panel exists for: a
     * block of text with no name on it cannot be scanned for who said it, and that is the first
     * question anyone asks of a transcript.
     */
    private HBox card(ChatMessage m, String author) {
        Region gutter = new Region();
        gutter.getStyleClass().add("chat-gutter");
        gutter.setStyle("-fx-background-color: " + colourFor(author) + ";");

        VBox content = new VBox(3);
        content.getStyleClass().add("chat-card-body");
        content.setMaxWidth(Double.MAX_VALUE);
        content.setFillWidth(true);
        HBox.setHgrow(content, Priority.ALWAYS);

        content.getChildren().add(header(m, author));
        if (m.hasQuote()) {
            content.getChildren().add(quoteNode(m.quoted()));
        }
        content.getChildren().add(bodyNode(m));

        HBox row = new HBox(gutter, content);
        row.getStyleClass().add("chat-card");
        // Without this the card is only ever as wide as the text it happens to hold: a box's
        // maximum defaults to its computed size, so it never grows into the space beside it and
        // the message wraps into a narrow column with the rest of the panel left empty.
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private HBox header(ChatMessage m, String author) {
        HBox head = new HBox(7);
        head.setAlignment(Pos.BASELINE_LEFT);

        Label name = new Label(author);
        name.getStyleClass().add("chat-author");
        // The colour is per-author and computed, so it stays in Java; everything else about the
        // name lives in the stylesheet.
        name.setStyle("-fx-text-fill: " + colourFor(author) + ";");
        head.getChildren().add(name);

        if (!m.allianceTag().isBlank()) {
            head.getChildren().add(chip(m.allianceTag()));
        }

        // The time is pushed to the far edge by a spacer rather than laid beside the name, so it
        // cannot end up sitting on top of the words when a name runs long.
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label time = new Label(TIME.format(m.capturedAt().atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("chat-time");
        head.getChildren().addAll(gap, time);
        head.setMaxWidth(Double.MAX_VALUE);
        return head;
    }

    /**
     * The message body: what was said, folded if it is a wall of it.
     *
     * <p>English only. The source text used to sit under every translation, and at a glance it read
     * as a second person talking rather than the same message twice. Nobody reading this speaks
     * every language in the alliance, which is the whole reason the translation is there.
     */
    private VBox bodyNode(ChatMessage m) {
        VBox box = new VBox(2);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);

        String shown = m.displayBody();
        box.getChildren().add(flowFor(shown, m));

        // A pasted strategy dump is one player's copy-paste and runs to five lines, burying the
        // conversation either side of it. Folded, it is still there to open.
        if (shown.length() > FOLD_LENGTH) {
            box.getChildren().set(0, flowFor(fold(shown), m));
            Label more = new Label(SHOW_MORE);
            more.getStyleClass().add("chat-more");
            more.setOnMouseClicked(e -> {
                boolean folded = SHOW_MORE.equals(more.getText());
                box.getChildren().set(0, flowFor(folded ? shown : fold(shown), m));
                more.setText(folded ? SHOW_LESS : SHOW_MORE);
            });
            box.getChildren().add(more);
        }
        return box;
    }

    private TextFlow flowFor(String text, ChatMessage m) {
        TextFlow flow = withMentions(text, bodyClass(m), m.mentions());
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private static String fold(String text) {
        return text.substring(0, FOLD_LENGTH).trim() + "\u2026";
    }

    /** Past this a message is a wall of text and is folded. */
    private static final int FOLD_LENGTH = 260;
    private static final String SHOW_MORE = "SHOW MORE";
    private static final String SHOW_LESS = "SHOW LESS";

    private static String bodyClass(ChatMessage m) {
        return switch (m.kind()) {
            case SYSTEM -> "chat-body-system";
            case EMOJI, STICKER -> "chat-body-emoji";
            default -> "chat-body";
        };
    }

    /**
     * The message being replied to, named and set behind a bar.
     *
     * <p>The game writes a quote as "Name: what they said". Saying "replying to" outright, and
     * setting the name apart from the words, lets a reader tell a reply to one person from a reply
     * to another without reading the strip. It sits under the header rather than above it --
     * emitted first, the person being quoted read as the person speaking, which is the single
     * thing that made the panel hardest to follow.
     */
    private VBox quoteNode(String quoted) {
        TextFlow flow = new TextFlow();
        Text marker = new Text("REPLYING TO  ");
        marker.getStyleClass().add("chat-reply-marker");
        flow.getChildren().add(marker);

        int colon = quoted.indexOf(':');
        if (colon > 0 && colon < MAX_QUOTED_NAME) {
            Text who = new Text(quoted.substring(0, colon).trim() + "  ");
            who.getStyleClass().add("chat-quote-who");
            flow.getChildren().add(who);
            flow.getChildren().addAll(mentionParts(quoted.substring(colon + 1).trim(),
                    "chat-quote-text", List.of()));
        } else {
            flow.getChildren().addAll(mentionParts(quoted, "chat-quote-text", List.of()));
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
    private TextFlow withMentions(String body, String bodyStyle, List<String> named) {
        return new TextFlow(mentionParts(body, bodyStyle, named).toArray(Text[]::new));
    }

    /**
     * Splits a line into ordinary words and the people named in it.
     *
     * <p>A mention is what makes a feed a conversation rather than a list -- it says who is talking
     * to whom -- and it is invisible when set in the same colour as everything else. Addressing the
     * whole alliance gets its own colour because the game draws it as its own thing, and a
     * transcript that flattens the two loses the difference between a broadcast and a reply.
     */
    private java.util.List<Text> mentionParts(String body, String bodyStyle, List<String> named) {
        java.util.List<Text> parts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = MENTION.matcher(body);
        int at = 0;
        while (m.find()) {
            String name = longestNamed(body, m.start(), m.group(), named);
            if (m.start() > at) {
                Text plain = new Text(body.substring(at, m.start()));
                plain.getStyleClass().add(bodyStyle);
                parts.add(plain);
            }
            Text mention = new Text(name);
            mention.getStyleClass().add(name.equalsIgnoreCase("@All")
                    ? "chat-mention-all" : "chat-mention");
            parts.add(mention);
            at = m.start() + name.length();
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

    /**
     * How far a mention actually runs.
     *
     * <p>Some players' names carry a space, so a mention cannot simply stop at the first one --
     * but nor can it always take the next capitalised word, which turned {@code "@Martinn No
     * worries"} into a mention of somebody called "Martinn No". The message already knows who it
     * named, parsed where the sender lines were available to check against, so the answer is looked
     * up rather than guessed: the longest name this message actually mentions wins, and where none
     * matches the mention is the one word.
     */
    private static String longestNamed(String body, int start, String oneWord,
            List<String> named) {
        String best = oneWord;
        for (String name : named) {
            String at = "@" + name;
            if (at.length() > best.length() && body.regionMatches(true, start, at, 0, at.length())) {
                best = body.substring(start, start + at.length());
            }
        }
        return best;
    }

    /** A name the game wrote with an "@" in front of it: one word unless the roster says longer. */
    private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile(
            "@[A-Za-z0-9_.-]{2,16}");

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

    /**
     * Where a reader keeps its transcript.
     *
     * <p>Mirrors what the capture routine writes. Kept as a plain path rather than shared with it
     * because the panel and the routine live in different modules, and one small duplicated string
     * is cheaper than a dependency between them.
     */
    private static Path baseDir(String reader) {
        Path base = Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
        return "JAVA".equals(reader) ? base.resolveSibling("chat-java") : base;
    }
}
