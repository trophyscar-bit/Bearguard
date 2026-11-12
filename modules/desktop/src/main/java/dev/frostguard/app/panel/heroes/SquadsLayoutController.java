package dev.frostguard.app.panel.heroes;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.SquadSnapshot;
import dev.frostguard.api.squads.TroopStock;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.squads.SquadPlanner;
import dev.frostguard.engine.squads.SquadScanRegistry;
import dev.frostguard.engine.squads.SquadSnapshotStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Troops > Squads: runs the hero/troop/squad scan and shows how to set up each squad. */
public final class SquadsLayoutController implements IProfileLoadListener {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private ProfileAux currentProfile;

    @FXML private Button buttonScan;
    @FXML private Label labelStatus;
    @FXML private Label labelProblems;
    @FXML private FlowPane cardsSuggested;
    @FXML private TableView<List<String>> tableSaved;
    @FXML private TableView<List<String>> tableTroops;

    @FXML
    private void initialize() {
        columns(tableSaved, "Squad", "State", "Heroes", "Infantry", "Lancer", "Marksman", "Capacity", "Power");
        columns(tableTroops, "Troop", "Type", "Count");
        showSuggestedEmpty();
        tableSaved.setPlaceholder(new Label("No saved squads read yet. Press Scan now."));
        tableTroops.setPlaceholder(new Label("No troop counts read yet. Press Scan now."));
        labelStatus.setText("Select a profile.");
        SquadScanRegistry.addListener(this::onScanEvent);
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        boolean changed = currentProfile == null || profile == null || !currentProfile.getId().equals(profile.getId());
        currentProfile = profile;
        if (changed) loadSaved();
    }

    @FXML
    private void handleScan() {
        ProfileAux selected = currentProfile;
        if (selected == null) {
            labelStatus.setText("Select a profile first.");
            return;
        }
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(selected.getId());
        if (queue == null || !queue.isActive()) {
            labelStatus.setText("Start the bot first. The scan runs through the profile task queue.");
            return;
        }
        SquadScanRegistry.requestManualScan(selected.getId());
        queue.runNow(TpDailyTaskEnum.SQUAD_SCAN, false);
        labelStatus.setText("Scan queued for " + selected.getName() + ". It takes a few minutes.");
    }

    private void onScanEvent(SquadScanRegistry.Event event) {
        ProfileAux selected = currentProfile;
        if (selected == null || !selected.getId().equals(event.profileId())) return;
        Platform.runLater(() -> {
            switch (event.state()) {
                case RUNNING -> {
                    buttonScan.setDisable(true);
                    labelStatus.setText(event.message());
                }
                case SUCCEEDED -> {
                    buttonScan.setDisable(false);
                    show(event.snapshot());
                }
                case FAILED -> {
                    buttonScan.setDisable(false);
                    labelStatus.setText("Scan failed: " + event.message());
                }
            }
        });
    }

    private void loadSaved() {
        clear();
        if (currentProfile == null) {
            labelStatus.setText("Select a profile.");
            return;
        }
        try {
            new SquadSnapshotStore(WorkspacePaths.current().root()).load(currentProfile.getId())
                    .ifPresentOrElse(this::show, () -> labelStatus.setText("No scan yet. Start the bot, then press Scan now."));
        } catch (IOException | RuntimeException e) {
            labelStatus.setText("Could not load the last scan: " + e.getMessage());
        }
    }

    // A snapshot the tables cannot take (an old file, a value the planner chokes on) must say so on
    // screen; inside Platform.runLater an exception would otherwise just leave every table empty.
    private void show(SquadSnapshot snapshot) {
        try {
            fill(snapshot);
        } catch (RuntimeException e) {
            labelProblems.setText("Could not display the last scan: " + e);
        }
    }

    private void fill(SquadSnapshot snapshot) {
        labelStatus.setText("Last scan " + when(snapshot.capturedAt()) + ": " + snapshot.heroes().size()
                + " heroes, " + snapshot.savedSquads().stream().filter(s -> s.state() == SavedSquad.State.SAVED).count()
                + " saved squads.");
        showProblems(snapshot);
        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot);

        cardsSuggested.getChildren().clear();
        for (SquadPlanner.Suggestion s : plan.squads()) {
            cardsSuggested.getChildren().add(card(s.purpose(), s.heroes(), s.why()));
        }
        if (!plan.exploration().isEmpty()) {
            cardsSuggested.getChildren().add(card("Exploration (5 heroes, any type)", plan.exploration(),
                    "Highest power. Exploration fights use hero stats, not troops."));
        }
        if (cardsSuggested.getChildren().isEmpty()) showSuggestedEmpty();

        List<List<String>> savedRows = new ArrayList<>();
        for (SavedSquad s : snapshot.savedSquads()) {
            List<Long> t = s.troops() == null ? List.of() : s.troops();
            savedRows.add(row(String.valueOf(s.slot()), state(s.state()),
                    s.heroes() == null || s.heroes().isEmpty() ? "-"
                            : s.heroes().stream().map(n -> n == null ? "?" : n).collect(Collectors.joining(", ")),
                    number(t.size() > 0 ? t.get(0) : null), number(t.size() > 1 ? t.get(1) : null),
                    number(t.size() > 2 ? t.get(2) : null), text(s.capacity()), number(s.power())));
        }
        tableSaved.setItems(FXCollections.observableArrayList(savedRows));

        List<List<String>> troopRows = new ArrayList<>();
        if (snapshot.troops() != null) {
            troopRows.add(row("Total troops", "", text(snapshot.troops().totalTroops())));
            troopRows.add(row("Injured", "", text(snapshot.troops().injured())));
            for (TroopStock stock : snapshot.troops().stocks()) {
                troopRows.add(row(stock.name(), stock.troopType(), number(stock.count())));
            }
        }
        tableTroops.setItems(FXCollections.observableArrayList(troopRows));
    }

    // One line on screen; the full list is a tooltip. Twenty-odd "card N could not be found again"
    // entries as a paragraph ran off the window and buried the tables.
    private void showProblems(SquadSnapshot snapshot) {
        List<String> problems = snapshot.problems();
        if (problems.isEmpty() && snapshot.troops() != null) {
            labelProblems.setText("");
            labelProblems.setTooltip(null);
            return;
        }
        String headline = snapshot.troops() == null
                ? "The last scan did not reach troops or saved squads. Press Scan now to retry."
                : "Some of the last scan could not be read (" + problems.size() + "). Hover for details.";
        labelProblems.setText(headline);
        labelProblems.setTooltip(problems.isEmpty() ? null : new Tooltip(String.join("\n", problems)));
    }

    private void showSuggestedEmpty() {
        Label empty = new Label("Press Scan now to get squad suggestions.");
        empty.setStyle("-fx-text-fill: #636a75; -fx-padding: 24;");
        cardsSuggested.getChildren().setAll(empty);
    }

    // One card per squad: what it is for, who leads it, and why those heroes.
    private static VBox card(String purpose, List<String> heroes, String why) {
        Label title = new Label(purpose);
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #fcd176;");

        FlowPane chips = new FlowPane(6, 6);
        if (heroes == null || heroes.isEmpty()) {
            Label none = new Label("No hero free for this slot");
            none.setStyle("-fx-text-fill: #636a75; -fx-font-style: italic;");
            chips.getChildren().add(none);
        } else {
            for (int i = 0; i < heroes.size(); i++) {
                Label chip = new Label(i == 0 ? heroes.get(i) + "  (leads)" : heroes.get(i));
                chip.setStyle("-fx-background-radius: 10; -fx-padding: 3 10 3 10; -fx-text-fill: "
                        + (i == 0 ? "#11141a; -fx-background-color: #fcd176; -fx-font-weight: bold;"
                                : "#e6edf3; -fx-background-color: #262a36;"));
                chips.getChildren().add(chip);
            }
        }

        Label reason = new Label(why == null ? "" : why);
        reason.setWrapText(true);
        reason.setStyle("-fx-text-fill: #9eaab6; -fx-font-size: 12px;");

        VBox box = new VBox(10, title, chips, reason);
        box.setPrefWidth(330);
        box.setMinWidth(330);
        box.setStyle("-fx-padding: 14; -fx-background-color: #181c24; -fx-background-radius: 10;"
                + " -fx-border-color: #262a36; -fx-border-radius: 10;");
        return box;
    }

    private void clear() {
        labelProblems.setText("");
        labelProblems.setTooltip(null);
        for (TableView<List<String>> table : List.of(tableSaved, tableTroops)) {
            table.getItems().clear();
        }
        showSuggestedEmpty();
    }

    private static void columns(TableView<List<String>> table, String... headers) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        for (int i = 0; i < headers.length; i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(headers[i]);
            column.setSortable(false);
            column.setCellValueFactory(row -> new ReadOnlyStringWrapper(
                    index < row.getValue().size() ? row.getValue().get(index) : ""));
            table.getColumns().add(column);
        }
    }

    private static String state(SavedSquad.State state) {
        return switch (state) {
            case SAVED -> "Saved";
            case EMPTY_OR_LOCKED -> "Empty, locked or unreadable";
            case SAME_AS_PREVIOUS -> "Empty, or the same squad as the slot before";
            case UNREADABLE -> "Unreadable";
        };
    }

    // A null cell (a name the scan could not read) shows as "-" instead of failing the whole table.
    private static List<String> row(String... cells) {
        List<String> row = new ArrayList<>(cells.length);
        for (String cell : cells) row.add(cell == null ? "-" : cell);
        return row;
    }

    private static String number(Long value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%,d", value);
    }

    private static String text(Object value) {
        return value == null ? "-" : Objects.toString(value);
    }

    private static String when(String iso) {
        try {
            return WHEN.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }
}
