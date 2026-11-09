package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentState;
import dev.frostguard.api.domain.TaskFailureStreakData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.panel.custom.CustomTasksLayoutController;
import dev.frostguard.app.panel.scheduler.TaskManagerAux;
import dev.frostguard.app.panel.scheduler.TaskManagerLayoutController;
import dev.frostguard.app.panel.today.TodayTimeline.Item;
import dev.frostguard.app.panel.today.TodayTimeline.Kind;
import dev.frostguard.app.panel.today.TodayTimeline.Task;
import dev.frostguard.data.repository.TaskFailureStreakRepository;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.listener.QueueStateListener;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ActionRequiredIncidentService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The Today page: one timeline for the selected profile and an inspector that follows the running
 * task. Listeners are registered once and only mark the page dirty while it is off screen.
 */
public final class TodayLayoutController implements IProfileLoadListener {

    private static final int MAX_SEARCH_RESULTS = 30;
    private static final double ROWS_ABOVE_NOW = 2.5;
    private static final int RELOAD_EVERY_SECONDS = 30;

    private final TaskManagerLayoutController taskManager;
    private final LongSupplier loadedProfileId;
    private final VBox root = new VBox();
    private final HBox timelineView = new HBox();
    private final StackPane controlSlot = new StackPane();
    private final ToggleButton timelineTab = new ToggleButton("Timeline");
    private final ToggleButton controlTab = new ToggleButton("Control");
    private final TodaySearchBar search;
    private final Label summary = new Label();
    private final Label summaryNote = new Label();
    private final VBox timeline = new VBox(1);
    private final TaskInspectorPanel inspector;
    private ScrollPane lineScroll;
    private Parent controlScreen;
    private boolean botRunning;
    private boolean botPaused;
    private final PauseTransition reloadDebounce = new PauseTransition(Duration.millis(250));
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> onClockTick()));
    private final Map<String, LocalDateTime> startedAt = new HashMap<>();
    private final Map<String, TaskManagerAux> rowsByKey = new HashMap<>();
    private final Map<String, Task> tasksByKey = new HashMap<>();
    private final Map<String, List<String>> failureLines = new HashMap<>();

    private Label nowLabel;
    private ProfileAux profile;
    private String selectedKey;
    private String runningKey;
    private boolean scrollToNow = true;
    private boolean following = true;
    private boolean dirty = true;
    private int secondsSinceReload;
    private long generation;

    public TodayLayoutController(TaskManagerLayoutController taskManager, LongSupplier loadedProfileId,
                                 Consumer<String> openScreen) {
        this.taskManager = taskManager;
        this.loadedProfileId = loadedProfileId;
        inspector = new TaskInspectorPanel(openScreen, this::resumeFollowing, this::runSelected);
        search = new TodaySearchBar(this::searchTasks, this::selectFromSearch, this::runFromSearch);
        buildLayout();
        clock.setCycleCount(Animation.INDEFINITE);
        reloadDebounce.setOnFinished(event -> reload());
        root.sceneProperty().addListener((observable, previous, scene) -> onVisibilityChanged(timelineShowing()));
        registerListeners();
    }

    public Parent root() {
        return root;
    }

    /** Supplied once the settings pages have loaded, which happens after this page is created. */
    public void attachSettings(Map<String, Object> controllers, Map<String, Parent> pages, Object eventsController,
                               CustomTasksLayoutController customTasks) {
        inspector.attachSettings(new TaskSettingsIndex(controllers, pages, eventsController, customTasks));
    }

    /** The Control screen is one node shared with its sidebar item, so it moves between the two. */
    public void attachControlScreen(Parent screen) {
        controlScreen = screen;
    }

    /** Called when Today is shown again, in case the sidebar's Control item took the screen away. */
    public void reattachControlScreen() {
        if (controlTab.isSelected() && controlScreen != null) {
            controlSlot.getChildren().setAll(controlScreen);
        }
    }

    private void showTab(boolean timeline) {
        setShown(timelineView, timeline);
        setShown(controlSlot, !timeline);
        if (timeline) {
            controlSlot.getChildren().clear();
        } else {
            search.close();
            reattachControlScreen();
        }
        onVisibilityChanged(timelineShowing());
    }

    private boolean timelineShowing() {
        return root.getScene() != null && timelineTab.isSelected();
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void buildLayout() {
        timelineView.getStyleClass().add("today-split");

        summary.getStyleClass().add("today-summary");
        summaryNote.getStyleClass().add("today-note");
        summaryNote.setWrapText(true);
        VBox verdict = new VBox(2, summary, summaryNote);
        verdict.getStyleClass().add("today-verdict");

        VBox line = new VBox(8, verdict, timeline);
        line.getStyleClass().add("today-line");

        lineScroll = new ScrollPane(line);
        lineScroll.setFitToWidth(true);
        lineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lineScroll.getStyleClass().add("task-page-scroll");
        VBox.setVgrow(lineScroll, Priority.ALWAYS);

        // The results float over the timeline rather than pushing it down as they change.
        StackPane lineArea = new StackPane(lineScroll, search.results());
        StackPane.setAlignment(search.results(), Pos.TOP_CENTER);
        VBox.setVgrow(lineArea, Priority.ALWAYS);
        VBox left = new VBox(6, search.field(), lineArea);
        left.getStyleClass().add("today-left");
        HBox.setHgrow(left, Priority.ALWAYS);
        timelineView.getChildren().addAll(left, inspector.root());

        ToggleGroup tabs = new ToggleGroup();
        timelineTab.setToggleGroup(tabs);
        controlTab.setToggleGroup(tabs);
        timelineTab.setSelected(true);
        timelineTab.getStyleClass().add("today-tab");
        controlTab.getStyleClass().add("today-tab");
        // A tab group must always have one selected, so a click on the open tab is undone.
        tabs.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
            } else {
                showTab(selected == timelineTab);
            }
        });
        HBox tabStrip = new HBox(4, timelineTab, controlTab);
        tabStrip.getStyleClass().add("today-tabs");

        StackPane content = new StackPane(timelineView, controlSlot);
        VBox.setVgrow(content, Priority.ALWAYS);
        setShown(controlSlot, false);
        root.getChildren().addAll(tabStrip, content);
    }

    private void registerListeners() {
        TaskManagementService.shared().subscribeStatusChanges((account, taskId, state) ->
                Platform.runLater(() -> onTaskChanged(account, taskId, state)));
        ScheduleService service = ScheduleService.obtain();
        service.addEngineObserver(BotStateListener.fromConsumer(state -> Platform.runLater(this::requestReload)));
        service.addQueueObserver(QueueStateListener.snapshotOnly(state -> Platform.runLater(this::requestReload)));
        ActionRequiredIncidentService.obtain().registerListener(
                incidents -> Platform.runLater(this::requestReload));
    }

    @Override
    public void onProfileLoad(ProfileAux loaded) {
        profile = loaded;
        selectedKey = null;
        following = true;
        search.close();
        requestReload();
    }

    private void onTaskChanged(Long account, int taskId, TaskStateData state) {
        if (state != null && state.isExecuting()) {
            startedAt.put(account + "/" + rowKey(taskId, state.getCustomTaskName()), LocalDateTime.now());
        }
        requestReload();
    }

    private void onVisibilityChanged(boolean shown) {
        if (shown) {
            clock.play();
            secondsSinceReload = 0;
            scrollToNow = true;
            if (dirty) {
                reload();
            }
        } else {
            clock.stop();
        }
    }

    private void onClockTick() {
        if (nowLabel != null) {
            nowLabel.setText("Now " + TodayTimeline.clock(LocalDateTime.now()));
        }
        if (++secondsSinceReload >= RELOAD_EVERY_SECONDS) {
            reload();
        }
    }

    private void requestReload() {
        if (!timelineShowing()) {
            dirty = true;
        } else {
            reloadDebounce.playFromStart();
        }
    }

    private Long currentProfileId() {
        if (profile != null && profile.getId() != null) {
            return profile.getId();
        }
        long loaded = loadedProfileId.getAsLong();
        return loaded > 0 ? loaded : null;
    }

    private void reload() {
        Long profileId = currentProfileId();
        if (profileId == null || !timelineShowing()) {
            dirty = true;
            return;
        }
        dirty = false;
        secondsSinceReload = 0;
        long requested = ++generation;
        taskManager.loadTaskRows(profileId, rows -> Thread.ofVirtual().name("today-failures").start(() -> {
            List<TaskFailureStreakData> streaks = TaskFailureStreakRepository.getRepository().findForProfile(profileId);
            List<ActionRequiredIncidentData> incidents = ActionRequiredIncidentService.obtain().findAll().stream()
                    .filter(incident -> profileId.equals(incident.profileId()))
                    .filter(incident -> incident.state() == ActionRequiredIncidentState.ACTIVE)
                    .toList();
            Platform.runLater(() -> {
                if (requested == generation) {
                    apply(profileId, rows, streaks, incidents);
                }
            });
        }));
    }

    private void apply(Long profileId, List<TaskManagerAux> rows, List<TaskFailureStreakData> streaks,
                       List<ActionRequiredIncidentData> incidents) {
        Map<String, FailureInfo> failures = indexFailures(streaks, incidents);
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
        botRunning = queue != null && queue.isActive();
        botPaused = queue != null && queue.isPaused();

        LocalDateTime now = LocalDateTime.now();
        rowsByKey.clear();
        tasksByKey.clear();
        failureLines.clear();
        List<Task> tasks = new ArrayList<>();
        for (TaskManagerAux row : rows) {
            Task task = toTask(row, botRunning, failures, now);
            tasks.add(task);
            rowsByKey.put(task.key(), row);
            tasksByKey.put(task.key(), task);
        }
        String previousRunning = runningKey;
        runningKey = tasks.stream().filter(Task::executing).map(Task::key).findFirst().orElse(null);
        scrollToNow |= following && runningKey != null && !runningKey.equals(previousRunning);

        List<Item> items = TodayTimeline.build(tasks, now, GameTimeUtils.dailyResetTime());
        if (following && runningKey != null) {
            selectedKey = runningKey;
        }
        if (selectedKey == null || !tasksByKey.containsKey(selectedKey)) {
            selectedKey = items.stream().filter(item -> item.task() != null && item.kind() != Kind.PAST)
                    .map(item -> item.task().key()).findFirst().orElse(null);
        }
        renderSummary(failures);
        renderTimeline(items, now);
        showInspector(profileId, now);
        search.refresh();
    }

    private record FailureInfo(String name, List<String> lines) {
    }

    private Map<String, FailureInfo> indexFailures(List<TaskFailureStreakData> streaks,
                                                   List<ActionRequiredIncidentData> incidents) {
        Map<String, FailureInfo> failures = new LinkedHashMap<>();
        for (TaskFailureStreakData streak : streaks) {
            if (streak.consecutiveFailures() > 0) {
                FailureInfo info = failures.computeIfAbsent(streak.taskKey(),
                        key -> new FailureInfo(displayNameOfKey(key), new ArrayList<>()));
                info.lines().add(streak.consecutiveFailures() + " failed in a row, last at "
                        + dayClock(streak.lastFailureAt()));
            }
        }
        for (ActionRequiredIncidentData incident : incidents) {
            FailureInfo info = failures.computeIfAbsent(incident.taskKey(),
                    key -> new FailureInfo(incident.taskName(), new ArrayList<>()));
            info.lines().add(incident.title());
            if (incident.cause() != null && !incident.cause().isBlank()) {
                info.lines().add(incident.cause());
            }
            if (incident.retryAt() != null) {
                info.lines().add("Next try " + dayClock(incident.retryAt()));
            }
        }
        return failures;
    }

    private static String displayNameOfKey(String key) {
        String base = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        try {
            return TpDailyTaskEnum.valueOf(base).getName();
        } catch (IllegalArgumentException notAnEnumName) {
            return base;
        }
    }

    private Task toTask(TaskManagerAux row, boolean botRunning, Map<String, FailureInfo> failures, LocalDateTime now) {
        TpDailyTaskEnum type = row.getTaskEnum();
        String custom = row.getCustomTaskName();
        String key = rowKey(type.getId(), custom);

        List<String> lines = failureLinesFor(type, custom, failures);
        if (!lines.isEmpty()) {
            failureLines.put(key, lines);
        }

        boolean scheduled = row.isScheduled();
        boolean savedOnly = false;
        if (!botRunning && !scheduled && row.getNextExecution() != null && isSwitchedOn(type, custom)) {
            scheduled = true;
            savedOnly = true;
        }
        boolean dueNow = row.isScheduled() && row.getNextExecution() != null && !row.getNextExecution().isAfter(now);
        return new Task(key, row.getTaskName(), row.getLastExecution(), row.getNextExecution(), scheduled,
                row.isExecuting(), dueNow, !lines.isEmpty(), savedOnly);
    }

    private static List<String> failureLinesFor(TpDailyTaskEnum type, String custom, Map<String, FailureInfo> failures) {
        boolean isCustom = custom != null && !custom.isBlank();
        String exact = isCustom ? type.name() + ":" + custom : type.name();
        List<String> lines = new ArrayList<>();
        failures.forEach((key, info) -> {
            if (key.equals(exact) || (!isCustom && key.startsWith(type.name() + ":"))) {
                lines.addAll(info.lines());
            }
        });
        return lines;
    }

    private boolean isSwitchedOn(TpDailyTaskEnum type, String custom) {
        if (custom != null && !custom.isBlank()) {
            return true;
        }
        ConfigurationKeyEnum key = type.getConfigKey();
        return key != null && profile != null && Boolean.TRUE.equals(profile.<Boolean>getConfiguration(key));
    }

    private static String rowKey(int taskId, String custom) {
        return custom == null || custom.isBlank() ? String.valueOf(taskId) : taskId + ":" + custom;
    }

    private void renderSummary(Map<String, FailureInfo> failures) {
        int count = failures.size();
        summary.setText(count == 0 ? "No tasks failing" : count + (count == 1 ? " task failing" : " tasks failing"));
        summary.getStyleClass().remove("today-summary-bad");
        if (count > 0) {
            summary.getStyleClass().add("today-summary-bad");
        }
        List<String> names = failures.values().stream().map(FailureInfo::name).distinct().toList();
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size())));
        summaryNote.setText(names.size() > 3 ? shown + " and " + (names.size() - 3) + " more" : shown);
        summaryNote.setVisible(!names.isEmpty());
        summaryNote.setManaged(!names.isEmpty());
    }

    private void renderTimeline(List<Item> items, LocalDateTime now) {
        List<Node> nodes = new ArrayList<>();
        for (Item item : items) {
            switch (item.kind()) {
                case NOW -> {
                    nowLabel = new Label("Now " + TodayTimeline.clock(now));
                    nodes.add(marker(nowLabel, "today-mark"));
                }
                case RESET -> nodes.add(marker(new Label("Daily reset " + rowTime(item.time(), now)), "today-mark-reset"));
                default -> nodes.add(row(item, now));
            }
        }
        timeline.getChildren().setAll(nodes);
        if (scrollToNow) {
            scrollToNow = false;
            Platform.runLater(this::revealNow);
        }
    }

    /** Keeps a little history above the Now marker, so what is next is what shows first. */
    private void revealNow() {
        Node marker = nowLabel == null ? null : nowLabel.getParent();
        if (marker == null) {
            return;
        }
        lineScroll.applyCss();
        lineScroll.layout();
        double content = lineScroll.getContent().getLayoutBounds().getHeight();
        double overflow = content - lineScroll.getViewportBounds().getHeight();
        if (overflow <= 0) {
            return;
        }
        double top = timeline.getBoundsInParent().getMinY() + marker.getBoundsInParent().getMinY();
        double rowHeight = marker.getBoundsInParent().getHeight();
        lineScroll.setVvalue(Math.max(0, Math.min(1, (top - ROWS_ABOVE_NOW * rowHeight) / overflow)));
    }

    private static Node marker(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        label.setMinWidth(Region.USE_PREF_SIZE);
        Region line = new Region();
        line.getStyleClass().add(styleClass + "-line");
        HBox.setHgrow(line, Priority.ALWAYS);
        HBox box = new HBox(8, label, line);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("today-mark-row");
        return box;
    }

    private Node row(Item item, LocalDateTime now) {
        Task task = item.task();

        Label time = new Label(item.kind() == Kind.RUNNING ? startedClock(task, now) : rowTime(item.time(), now));
        time.getStyleClass().add("today-time");
        time.setMinWidth(82);
        time.setPrefWidth(82);

        Region dot = new Region();
        dot.getStyleClass().addAll("today-dot", dotClass(item.kind(), task));
        dot.setMinSize(9, 9);
        dot.setMaxSize(9, 9);

        Label name = new Label(task.name());
        name.getStyleClass().add("today-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label status = new Label(rowStatus(item, now));
        status.getStyleClass().add("today-note");
        status.setMinWidth(Region.USE_PREF_SIZE);

        HBox box = new HBox(8, time, dot, name, status);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("today-row");
        if (item.kind() == Kind.PAST) {
            box.getStyleClass().add("today-row-past");
        }
        if (task.key().equals(selectedKey)) {
            box.getStyleClass().add("today-row-selected");
        }
        box.setOnMouseClicked(event -> onRowClicked(task.key()));
        return box;
    }

    private static String dotClass(Kind kind, Task task) {
        if (task.failing()) {
            return "today-dot-bad";
        }
        return switch (kind) {
            case RUNNING -> "today-dot-run";
            case PAST -> "today-dot-past";
            default -> "today-dot-next";
        };
    }

    private static String rowStatus(Item item, LocalDateTime now) {
        Task task = item.task();
        String failing = task.failing() ? "Failing" : "";
        return switch (item.kind()) {
            case RUNNING -> "Running";
            case UPCOMING -> {
                String when = TodayTimeline.relative(now, item.time());
                yield task.failing() ? failing + ", " + when : when;
            }
            default -> failing;
        };
    }

    private String startedClock(Task task, LocalDateTime now) {
        LocalDateTime started = startedAt.get(currentProfileId() + "/" + task.key());
        return TodayTimeline.clock(started == null ? now : started);
    }

    private static String rowTime(LocalDateTime time, LocalDateTime now) {
        return TodayTimeline.clockWithDay(time, now);
    }

    private static String dayClock(LocalDateTime time) {
        return time == null ? "unknown" : rowTime(time, LocalDateTime.now());
    }

    private void onRowClicked(String key) {
        following = key.equals(runningKey);
        selectedKey = key;
        requestReload();
    }

    private void resumeFollowing() {
        following = true;
        if (runningKey != null) {
            selectedKey = runningKey;
        }
        requestReload();
    }

    private void runSelected() {
        TaskManagerAux row = rowsByKey.get(selectedKey);
        if (row != null) {
            taskManager.runTaskNow(row);
            requestReload();
        }
    }

    private RunNowPolicy.Decision runDecision(Task task, TaskManagerAux row) {
        return RunNowPolicy.decide(botRunning, botPaused, task.executing(), row.isScheduled());
    }

    private List<TodaySearchBar.Hit> searchTasks(String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        return tasksByKey.values().stream()
                .filter(task -> task.name().toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((Task task) -> !task.name().toLowerCase(Locale.ROOT).startsWith(wanted))
                        .thenComparing(Task::name))
                .limit(MAX_SEARCH_RESULTS)
                .map(task -> new TodaySearchBar.Hit(task.key(), task.name(), searchState(task, now),
                        runDecision(task, rowsByKey.get(task.key()))))
                .toList();
    }

    private static String searchState(Task task, LocalDateTime now) {
        if (task.executing()) {
            return "Running";
        }
        if (!task.scheduled() || task.next() == null) {
            return "Off";
        }
        return "Next " + rowTime(task.next(), now);
    }

    private void selectFromSearch(String key) {
        following = false;
        selectedKey = key;
        showInspector(currentProfileId(), LocalDateTime.now());
    }

    /** Uses the same guards and the same run path as the panel's button. */
    private boolean runFromSearch(String key) {
        Task task = tasksByKey.get(key);
        TaskManagerAux row = rowsByKey.get(key);
        if (task == null || row == null) {
            return false;
        }
        RunNowPolicy.Decision decision = runDecision(task, row);
        if (!decision.enabled()) {
            return false;
        }
        selectedKey = key;
        following = false;
        taskManager.runTaskNow(row);
        inspector.showRunNote(decision.afterRun());
        requestReload();
        return true;
    }

    private void showInspector(Long profileId, LocalDateTime now) {
        Task task = selectedKey == null ? null : tasksByKey.get(selectedKey);
        if (task == null) {
            inspector.showNothing();
            return;
        }
        TaskManagerAux row = rowsByKey.get(selectedKey);
        RunNowPolicy.Decision runNow = runDecision(task, row);
        inspector.show(new TaskInspectorPanel.Detail(
                task.key(), task.name(), row.getTaskEnum(), row.isCustomTask() ? row.getCustomTaskName() : null,
                statusOf(task, botRunning, now),
                task.executing() ? "Started" : "Scheduled",
                task.executing() ? startedText(profileId, task) : scheduledText(task, now),
                task.last() == null ? "Never" : dayClock(task.last()),
                failureLines.getOrDefault(task.key(), List.of()), runNow, following));
    }

    private String startedText(Long profileId, Task task) {
        LocalDateTime started = startedAt.get(profileId + "/" + task.key());
        return started == null ? "Before this app opened" : dayClock(started);
    }

    private static String scheduledText(Task task, LocalDateTime now) {
        if (task.next() == null || !task.scheduled()) {
            return "Not scheduled";
        }
        return rowTime(task.next(), now) + " (" + TodayTimeline.relative(now, task.next()) + ")";
    }

    private static String statusOf(Task task, boolean botRunning, LocalDateTime now) {
        if (task.executing()) {
            return "Running";
        }
        if (task.failing()) {
            return "Failing";
        }
        if (task.savedOnly() || !botRunning) {
            return task.scheduled() ? "Bot is stopped, saved schedule" : "Off";
        }
        if (task.dueNow()) {
            return "Due now";
        }
        return task.scheduled() ? "Waiting, " + TodayTimeline.relative(now, task.next()) : "Off";
    }
}
