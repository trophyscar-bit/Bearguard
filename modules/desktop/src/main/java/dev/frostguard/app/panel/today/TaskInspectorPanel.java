package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** The 310px panel beside the timeline: one task's status, failure reasons, Run now and settings. */
final class TaskInspectorPanel {

    record Detail(String key, String name, TpDailyTaskEnum type, String customTask, String status, String timeCaption, String timeText,
                  String lastRun, List<String> failure, RunNowPolicy.Decision runNow, boolean following) {
    }

    private record Prepared(TaskSettingsMirror.Result mirror, String pageTitle) {
    }

    private static final double WIDTH = 310;

    private final ScrollPane root;
    private final Consumer<String> openScreen;
    private final Runnable onRun;
    private final Map<TpDailyTaskEnum, Prepared> preparedByTask = new HashMap<>();
    private final Map<Node, Prepared> preparedByCustomCard = new HashMap<>();
    private final Button followButton = new Button("Follow running task");
    private final Label title = new Label();
    private final HBox header = new HBox(8);
    private final Label status = valueLabel();
    private final Label timeCaption = captionLabel("Scheduled");
    private final Label timeValue = valueLabel();
    private final Label lastRun = valueLabel();
    private final Label failureCaption = sectionLabel("Why it's failing");
    private final VBox failure = new VBox(4);
    private final Button runButton = new Button();
    private final Label runNote = new Label();
    private final Label settingsCaption = sectionLabel("Settings");
    private final VBox settings = new VBox(7);
    private final Hyperlink allSettings = new Hyperlink();
    private final Label noSettings = new Label();

    private TaskSettingsIndex index;
    private String shownKey;
    private String runAfterNote;

    TaskInspectorPanel(Consumer<String> openScreen, Runnable onFollow, Runnable onRun) {
        this.openScreen = openScreen;
        this.onRun = onRun;
        followButton.setOnAction(event -> onFollow.run());
        followButton.setMaxWidth(Double.MAX_VALUE);
        followButton.getStyleClass().add("today-follow");

        title.setWrapText(true);
        title.getStyleClass().add("today-inspector-title");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane facts = new GridPane();
        facts.setHgap(10);
        facts.setVgap(4);
        facts.addRow(0, captionLabel("Status"), status);
        facts.addRow(1, timeCaption, timeValue);
        facts.addRow(2, captionLabel("Last run"), lastRun);

        runButton.setOnAction(event -> runPressed());
        runButton.getStyleClass().add("action-button-timeline");
        runNote.getStyleClass().add("today-note");
        runNote.setWrapText(true);
        HBox run = new HBox(10, runButton, runNote);
        run.setAlignment(Pos.CENTER_LEFT);

        noSettings.getStyleClass().add("today-note");

        VBox body = new VBox(10, followButton, header, facts, run, failureCaption, failure,
                settingsCaption, settings, noSettings, allSettings);
        body.getStyleClass().add("today-inspector");

        root = new ScrollPane(body);
        root.setFitToWidth(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.getStyleClass().add("task-page-scroll");
        root.setMinWidth(WIDTH);
        root.setPrefWidth(WIDTH);
        root.setMaxWidth(WIDTH);
        showNothing();
    }

    Parent root() {
        return root;
    }

    void attachSettings(TaskSettingsIndex settingsIndex) {
        index = settingsIndex;
        preparedByTask.clear();
        preparedByCustomCard.clear();
        shownKey = null;
    }

    void showNothing() {
        shownKey = null;
        title.setText("No task selected");
        header.getChildren().setAll(title);
        status.setText("");
        timeValue.setText("");
        lastRun.setText("");
        runButton.setText("Run now");
        runButton.setDisable(true);
        runNote.setText("");
        setShown(followButton, false);
        setShown(failureCaption, false);
        setShown(failure, false);
        setShown(settingsCaption, false);
        setShown(settings, false);
        setShown(noSettings, false);
        setShown(allSettings, false);
    }

    void show(Detail detail) {
        setShown(followButton, !detail.following());
        title.setText(detail.name());
        status.setText(detail.status());
        timeCaption.setText(detail.timeCaption());
        timeValue.setText(detail.timeText());
        lastRun.setText(detail.lastRun());
        showFailure(detail.failure());

        boolean sameTask = detail.key().equals(shownKey);
        runButton.setText(detail.runNow().label());
        runButton.setDisable(!detail.runNow().enabled());
        // A note written by pressing the button stays until the task or its state changes.
        if (!sameTask || detail.runNow().afterRun() == null) {
            runNote.setText(detail.runNow().note() == null ? "" : detail.runNow().note());
        }
        runAfterNote = detail.runNow().afterRun();
        if (!sameTask) {
            showSettings(detail.type(), detail.customTask());
            shownKey = detail.key();
        }
    }

    private void runPressed() {
        onRun.run();
        if (runAfterNote != null) {
            runNote.setText(runAfterNote);
        }
    }

    private void showFailure(List<String> lines) {
        boolean failing = !lines.isEmpty();
        setShown(failureCaption, failing);
        setShown(failure, failing);
        failure.getChildren().setAll(lines.stream().map(text -> {
            Label line = new Label(text);
            line.setWrapText(true);
            line.getStyleClass().add("today-failure");
            return (Node) line;
        }).toList());
    }

    private void showSettings(TpDailyTaskEnum type, String customTask) {
        Optional<Prepared> prepared = prepare(type, customTask);
        boolean hasRows = prepared.isPresent() && !prepared.get().mirror().rows().isEmpty();
        setShown(settingsCaption, hasRows);
        setShown(settings, hasRows);
        settings.getChildren().setAll(hasRows ? prepared.get().mirror().rows() : List.of());
        CheckBox toggle = prepared.map(value -> value.mirror().toggle()).orElse(null);
        setShown(noSettings, !hasRows);
        noSettings.setText(toggle == null ? "No settings for this task" : "No other settings for this task");

        if (toggle == null) {
            header.getChildren().setAll(title);
        } else {
            header.getChildren().setAll(title, toggle);
        }

        setShown(allSettings, prepared.isPresent());
        prepared.ifPresent(value -> {
            allSettings.setText("All " + value.pageTitle() + " settings");
            allSettings.setOnAction(event -> openScreen.accept(value.pageTitle()));
        });
    }

    /** Run from the search box rather than this panel's button, so the note is set from outside. */
    void showRunNote(String note) {
        if (note != null) {
            runNote.setText(note);
        }
    }

    private Optional<Prepared> prepare(TpDailyTaskEnum type, String customTask) {
        if (index == null) {
            return Optional.empty();
        }
        if (customTask != null) {
            // Keyed by the card's own switch, so a re-imported task gets a mirror of its new card.
            return index.findCustom(customTask).map(source -> preparedByCustomCard.computeIfAbsent(
                    source.activation(), card -> new Prepared(TaskSettingsMirror.build(source), source.pageTitle())));
        }
        Prepared cached = preparedByTask.get(type);
        if (cached != null) {
            return Optional.of(cached);
        }
        return index.find(type).map(source -> {
            Prepared built = new Prepared(TaskSettingsMirror.build(source), source.pageTitle());
            preparedByTask.put(type, built);
            return built;
        });
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static Label valueLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.getStyleClass().add("today-value");
        return label;
    }

    private static Label captionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("today-note");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("today-section");
        return label;
    }
}
