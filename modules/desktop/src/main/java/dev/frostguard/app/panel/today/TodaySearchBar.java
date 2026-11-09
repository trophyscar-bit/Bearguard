package dev.frostguard.app.panel.today;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Finds any task of the selected profile by name and runs it without leaving Today. The results
 * sit in the page rather than a popup window, so they stay inside the app and take no focus.
 */
final class TodaySearchBar {

    /** One result: what the row shows and what its button may do. */
    record Hit(String key, String name, String state, RunNowPolicy.Decision decision) {
    }

    private static final double MAX_RESULTS_HEIGHT = 300;

    private final Function<String, List<Hit>> search;
    private final Consumer<String> onSelect;
    private final Predicate<String> onRun;
    private final TextField field = new TextField();
    private final VBox rows = new VBox(1);
    private final Label footer = new Label();
    private final VBox dropdown;

    private List<Hit> hits = List.of();
    private int highlighted = -1;

    /**
     * @param search   the results for what was typed
     * @param onSelect shows a task in the side panel
     * @param onRun    runs a task, returning false when its guard refused
     */
    TodaySearchBar(Function<String, List<Hit>> search, Consumer<String> onSelect, Predicate<String> onRun) {
        this.search = search;
        this.onSelect = onSelect;
        this.onRun = onRun;

        field.setPromptText("Run a task: type its name");
        field.getStyleClass().add("today-search");
        field.textProperty().addListener((observable, previous, text) -> onTextChanged(text));
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        field.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) {
                close();
            }
        });

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.setMaxHeight(MAX_RESULTS_HEIGHT);
        scroll.getStyleClass().add("today-search-scroll");

        footer.getStyleClass().add("today-note");
        footer.setWrapText(true);

        dropdown = new VBox(4, scroll, footer);
        dropdown.getStyleClass().add("today-search-results");
        dropdown.setMaxHeight(Region.USE_PREF_SIZE);
        setShown(dropdown, false);
    }

    Node field() {
        return field;
    }

    Node results() {
        return dropdown;
    }

    boolean isOpen() {
        return dropdown.isVisible();
    }

    /** The task list changed underneath an open dropdown; the same task stays highlighted. */
    void refresh() {
        if (isOpen()) {
            String keep = highlighted >= 0 && highlighted < hits.size() ? hits.get(highlighted).key() : null;
            hits = search.apply(field.getText());
            highlighted = indexOf(keep);
            render();
        }
    }

    void close() {
        setShown(dropdown, false);
        hits = List.of();
        highlighted = -1;
    }

    private int indexOf(String key) {
        for (int index = 0; index < hits.size(); index++) {
            if (hits.get(index).key().equals(key)) {
                return index;
            }
        }
        return hits.isEmpty() ? -1 : 0;
    }

    private void onTextChanged(String text) {
        if (text == null || text.isBlank()) {
            close();
            return;
        }
        hits = search.apply(text);
        highlighted = hits.isEmpty() ? -1 : 0;
        setShown(dropdown, true);
        render();
        selectHighlighted();
    }

    private void onKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case DOWN -> move(1);
            case UP -> move(-1);
            case ENTER -> runHighlighted();
            case ESCAPE -> close();
            default -> {
                return;
            }
        }
        event.consume();
    }

    private void move(int step) {
        if (!isOpen() || hits.isEmpty()) {
            return;
        }
        highlighted = Math.floorMod(highlighted + step, hits.size());
        render();
        selectHighlighted();
    }

    private void selectHighlighted() {
        if (highlighted >= 0) {
            onSelect.accept(hits.get(highlighted).key());
        }
    }

    private void runHighlighted() {
        if (isOpen() && highlighted >= 0) {
            run(hits.get(highlighted));
        }
    }

    private void run(Hit hit) {
        if (hit.decision().enabled() && onRun.test(hit.key())) {
            field.clear();
        }
    }

    private void render() {
        if (hits.isEmpty()) {
            rows.getChildren().setAll(new Label("No task matches"));
            footer.setText("");
            setShown(footer, false);
            return;
        }
        rows.getChildren().setAll(java.util.stream.IntStream.range(0, hits.size()).mapToObj(this::row).toList());
        String note = highlighted >= 0 ? hits.get(highlighted).decision().note() : null;
        footer.setText(note == null ? "" : note);
        setShown(footer, note != null);
    }

    private Node row(int index) {
        Hit hit = hits.get(index);
        Label name = new Label(hit.name());
        name.getStyleClass().add("today-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label state = new Label(hit.state());
        state.getStyleClass().add("today-note");
        state.setMinWidth(Region.USE_PREF_SIZE);

        Button run = new Button(hit.decision().label());
        run.getStyleClass().add("today-search-run");
        run.setFocusTraversable(false);
        run.setMinWidth(Region.USE_PREF_SIZE);
        run.setDisable(!hit.decision().enabled());
        if (hit.decision().note() != null) {
            run.setTooltip(new Tooltip(hit.decision().note()));
        }
        run.setOnAction(event -> run(hit));

        HBox row = new HBox(8, name, state, run);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("today-search-row");
        if (index == highlighted) {
            row.getStyleClass().add("today-search-row-active");
        }
        row.setOnMouseClicked(event -> {
            highlighted = index;
            render();
            selectHighlighted();
        });
        return row;
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
