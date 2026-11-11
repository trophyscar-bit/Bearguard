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

/** Troops > Squads: runs the hero/troop/squad scan and shows how to set up each squad. */
public final class SquadsLayoutController implements IProfileLoadListener {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private ProfileAux currentProfile;

    @FXML private Button buttonScan;
    @FXML private Label labelStatus;
    @FXML private Label labelProblems;
    @FXML private TableView<List<String>> tableSuggested;
    @FXML private TableView<List<String>> tableSaved;
    @FXML private TableView<List<String>> tableTroops;

    @FXML
    private void initialize() {
        columns(tableSuggested, "Squad", "Use", "Heroes (first = leads the march)", "Why");
        columns(tableSaved, "Squad", "State", "Heroes", "Infantry", "Lancer", "Marksman", "Capacity", "Power");
        columns(tableTroops, "Troop", "Type", "Count");
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
        labelProblems.setText(snapshot.problems().isEmpty() ? ""
                : "Could not read: " + String.join("; ", snapshot.problems()));
        SquadPlanner.Plan plan = SquadPlanner.plan(snapshot);

        List<List<String>> suggested = new ArrayList<>();
        for (SquadPlanner.Suggestion s : plan.squads()) {
            suggested.add(row(String.valueOf(s.slot()), s.purpose(),
                    s.heroes().isEmpty() ? "-" : String.join(", ", s.heroes()), s.why()));
        }
        if (!plan.exploration().isEmpty()) {
            suggested.add(row("-", "Exploration (5 heroes, any type)", String.join(", ", plan.exploration()),
                    "Highest power. Exploration fights use hero stats, not troops."));
        }
        tableSuggested.setItems(FXCollections.observableArrayList(suggested));

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

    private void clear() {
        labelProblems.setText("");
        for (TableView<List<String>> table : List.of(tableSuggested, tableSaved, tableTroops)) {
            table.getItems().clear();
        }
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
