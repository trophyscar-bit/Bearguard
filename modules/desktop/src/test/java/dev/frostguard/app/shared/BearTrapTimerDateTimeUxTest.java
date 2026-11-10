package dev.frostguard.app.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.controlsfx.control.CheckComboBox;

import javax.imageio.ImageIO;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.combat.BearTrapLayoutController;
import dev.frostguard.app.panel.profile.ConfigAux;
import dev.frostguard.app.panel.profile.ProfileAux;
import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

class BearTrapTimerDateTimeUxTest {

    private static final String KEEP_RUNNING = "Keep everything running";
    private static final String NO_NEW_RALLIES = "Keep other tasks running, but don't start new rallies";
    private static final String PAUSE_OTHERS = "Pause all other tasks";

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
    }

    @Test
    void parsesExistingValuesAndFormatsTheSamePersistenceShape() {
        LocalDateTime expected = LocalDateTime.of(2026, 7, 26, 0, 30);

        assertEquals(expected, UtcDateTimeValue.parsePersisted("26-07-2026 00:30").orElseThrow());
        assertEquals("26-07-2026 00:30", UtcDateTimeValue.formatPersisted(expected));
        assertTrue(UtcDateTimeValue.parsePersisted("").isEmpty());
        assertTrue(UtcDateTimeValue.parsePersisted("2026-07-26T00:30").isEmpty());
    }

    @Test
    void convertsUtcPreviewAcrossThePreviousLocalDate() {
        String preview = UtcDateTimeValue.formatLocalPreview(
                LocalDateTime.of(2026, 7, 27, 0, 30),
                ZoneId.of("America/New_York"));

        assertEquals("Next: 26-07-2026 20:30 EDT = 27-07-2026 00:30 UTC", preview);
    }

    @Test
    void showsTheLocalTimeBesideTheStoredUtcTime() {
        String preview = UtcDateTimeValue.formatLocalPreview(
                LocalDateTime.of(2026, 11, 10, 19, 0),
                ZoneId.of("America/New_York"));

        assertEquals("Next: 10-11-2026 14:00 EST = 19:00 UTC", preview);
        assertEquals("14:00 EST", UtcDateTimeValue.formatLocalTime(
                LocalDateTime.of(2026, 11, 10, 19, 0), ZoneId.of("America/New_York")));
    }

    @Test
    void calculatesTheNextActivationFromTheFortyEightHourAnchor() {
        LocalDateTime anchor = LocalDateTime.of(2026, 7, 25, 0, 30);
        Clock afterTwoCycles = Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneId.of("UTC"));

        assertEquals(LocalDateTime.of(2026, 7, 29, 0, 30),
                UtcDateTimeValue.nextActivation(anchor, afterTwoCycles));
        assertEquals(anchor, UtcDateTimeValue.nextActivation(anchor,
                Clock.fixed(Instant.parse("2026-07-24T23:00:00Z"), ZoneId.of("UTC"))));
    }

    @Test
    void rejectsIncompleteAndInvalidSelections() {
        assertEquals(UtcDateTimeValue.Validation.INCOMPLETE,
                UtcDateTimeValue.resolve(null, "12", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INCOMPLETE,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "24", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "12", "60").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolveDateText("31-02-2026", "12", "30").validation());
    }

    @Test
    void editorCommitsOnceOnlyAfterApplyAndShowsImmediatePreview() throws Exception {
        runOnFxThread(() -> {
            UtcDateTimeEditor editor = new UtcDateTimeEditor(
                    ZoneId.of("America/New_York"),
                    Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneId.of("UTC")));
            List<LocalDateTime> commits = new ArrayList<>();
            AtomicBoolean cleared = new AtomicBoolean();
            editor.setOnCommit(commits::add);
            editor.setOnClear(() -> cleared.set(true));
            editor.timerEnabledProperty().set(true);
            assertTrue(editor.datePicker().isEditable());
            assertEquals("dd-MM-yyyy, e.g. 28-07-2026", editor.datePicker().getPromptText());

            editor.setDateTime(LocalDateTime.of(2026, 7, 26, 19, 15));
            assertTrue(commits.isEmpty(), "loading must not persist the existing value");

            editor.setDraft(LocalDate.of(2026, 7, 27), "00", "30");
            assertEquals("Next: 28-07-2026 20:30 EDT = 29-07-2026 00:30 UTC", editor.getLocalPreviewText());
            assertTrue(commits.isEmpty(), "individual control changes must remain a draft");

            editor.commitSelection();
            assertEquals(List.of(LocalDateTime.of(2026, 7, 27, 0, 30)), commits);
            assertEquals("00", editor.hourSpinner().getEditor().getText());
            assertEquals("30", editor.minuteSpinner().getEditor().getText());
            assertEquals(82, editor.hourSpinner().getMinWidth());
            assertEquals(82, editor.minuteSpinner().getMinWidth());

            editor.setDraft(LocalDate.of(2026, 7, 28), "99", "30");
            assertFalse(editor.getValidationMessage().isEmpty());
            assertTrue(editor.datePicker().getEditor().getStyleClass().contains("setting-field-error"));
            assertFalse(editor.datePicker().getEditor().getAccessibleHelp().isBlank());
            editor.commitSelection();
            assertEquals(1, commits.size(), "invalid input must not be persisted");

            editor.setDateTextDraft("31-02-2026", "12", "30");
            assertFalse(editor.getValidationMessage().isEmpty());
            editor.commitSelection();
            assertEquals(1, commits.size(), "invalid date text must not reuse the previous date");

            editor.clearSelection();
            assertTrue(cleared.get());
            assertFalse(editor.hasCommittedDateTime());
            assertNull(editor.getDateTime());
            return null;
        });
    }

    @Test
    void sharedTimeBindingPreservesInvalidDraftAndSuppressesPersistence() throws Exception {
        runOnFxThread(() -> {
            TextField field = new TextField();
            Label error = new Label();
            List<LocalTime> commits = new ArrayList<>();
            new ValidatedTextFieldBinding<>(
                    field,
                    error,
                    SettingValidators.localTime("Activation time"),
                    value -> String.format("%02d:%02d", value.getHour(), value.getMinute()),
                    commits::add,
                    text -> text != null && text.matches("\\d{4}")
                            ? text.substring(0, 2) + ":" + text.substring(2)
                            : text);

            field.setText("99:00");
            field.fireEvent(new ActionEvent());

            assertEquals("99:00", field.getText());
            assertTrue(error.isVisible());
            assertTrue(commits.isEmpty());

            field.setText("0930");
            field.fireEvent(new ActionEvent());

            assertEquals("09:30", field.getText());
            assertEquals(List.of(LocalTime.of(9, 30)), commits);
            assertFalse(error.isVisible());
            return null;
        });
    }

    @Test
    void profileIntegerFieldRejectsCharactersBeforeValidation() throws Exception {
        runOnFxThread(() -> {
            TestProfileController controller = new TestProfileController();
            TextField field = new TextField();
            List<Change> changes = new ArrayList<>();
            controller.register(field, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
            controller.attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            controller.startListening();

            field.setText("invalid");
            field.fireEvent(new ActionEvent());

            assertEquals("", field.getText());
            assertTrue(field.getStyleClass().contains("setting-field-error"));
            assertTrue(changes.isEmpty());

            field.setText("2");
            field.fireEvent(new ActionEvent());

            assertFalse(field.getStyleClass().contains("setting-field-error"));
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2)), changes);
            return null;
        });
    }

    private static List<Change> listen(LoadedBearView view) {
        List<Change> changes = new ArrayList<>();
        view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
        return changes;
    }

    private static ProfileAux liveProfile() {
        return profile(1L,
                config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING, "4,5,6"),
                config(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, "10"),
                config(ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"));
    }

    /** Runs whatever the page queued for after the current change event. */
    private static void settle() throws Exception {
        runOnFxThread(() -> null);
    }

    private static void noWritesOnLoad(ProfileAux profile) throws Exception {
        AtomicReference<List<Change>> seen = new AtomicReference<>();
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            seen.set(listen(view));
            view.controller().onProfileLoad(profile);
            return null;
        });
        settle();
        assertTrue(seen.get().isEmpty(), "loading must save nothing, saw " + seen.get());
    }

    @Test
    void loadingTheLiveSettingsShowsThemAndSavesNothing() throws Exception {
        noWritesOnLoad(liveProfile());
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            view.controller().onProfileLoad(liveProfile());

            assertEquals(LocalDateTime.of(2026, 11, 10, 19, 0), view.start().getDateTime());
            assertEquals("10", view.lead().getText());
            assertEquals(NO_NEW_RALLIES, String.valueOf(view.guard().getValue()));
            assertEquals(List.of(1, 2, 3), view.hero().getCheckModel().getCheckedItems());
            assertEquals(List.of(4, 5, 6), view.troop().getCheckModel().getCheckedItems());
            assertTrue(view.icon().isSelected());
            return null;
        });
    }

    @Test
    void switchingProfilesKeepsEachProfilesOwnSquads() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);
            view.controller().onProfileLoad(liveProfile());

            view.controller().onProfileLoad(profile(2L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "4,5"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING, "1")));

            assertEquals(List.of(4, 5), view.hero().getCheckModel().getCheckedItems());
            assertEquals(List.of(1), view.troop().getCheckModel().getCheckedItems());
            assertNull(view.start().getDateTime());
            assertTrue(changes.isEmpty());
            return null;
        });
    }

    @Test
    void thePageReadsRolesThenTrapThenTimeInPlainWords() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<String> captions = SceneGraphWalk.descendants(view.root()).stream()
                    .filter(node -> node instanceof Labeled)
                    .map(node -> ((Labeled) node).getText())
                    .filter(text -> text != null && !text.isBlank())
                    .filter(text -> !text.contains("Hero squad to join"))
                    .toList();

            assertEquals(List.of(
                    "LEADER", "Turn off \"Start when the bear icon appears\" to lead.", "Rally squad",
                    "Join with", "Hero squads", "Troop squads",
                    "JOINER", "ACTIVE", "Join rallies is off, so you will not rally.",
                    "Join with", "Hero squads", "Troop squads", "Start when the bear icon appears",
                    "Auto bear", "Trap", "Bear start (UTC)", ":", "UTC", "Apply", "Clear", "Next: —",
                    "Get ready (minutes before)",
                    "Stops auto-join, and recalls gatherers if ticked, this many minutes before the bear.",
                    "During the bear",
                    "Both roles", "Join rallies", "Recall gatherers", "Use pet skills"), captions);
            assertEquals(List.of(KEEP_RUNNING, NO_NEW_RALLIES, PAUSE_OTHERS),
                    view.guard().getItems().stream().map(String::valueOf).toList());
            return null;
        });
    }

    @Test
    void leadAcceptsOneToSixtyAndABlankTakesTheDefault() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);
            view.controller().onProfileLoad(liveProfile());

            for (String rejected : List.of("0", "61")) {
                view.lead().setText(rejected);
                view.lead().fireEvent(new ActionEvent());
                assertTrue(changes.isEmpty(), rejected + " must not save");
                assertTrue(view.lead().getStyleClass().contains("setting-field-error"));
            }

            view.lead().setText("60");
            view.lead().fireEvent(new ActionEvent());
            view.lead().setText("");
            view.lead().fireEvent(new ActionEvent());

            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 60),
                    new Change(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 15)), changes);
            return null;
        });
    }

    @Test
    void theGuardSavesAllThreeKeysAndAnIntentionalOffSurvivesEditingTheTime() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);
            view.controller().onProfileLoad(profile(6L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "02-08-2026 19:00"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "false")));
            assertEquals(KEEP_RUNNING, String.valueOf(view.guard().getValue()));

            view.start().setDraft(LocalDate.of(2026, 8, 4), "19", "30");
            view.start().commitSelection();
            assertEquals(KEEP_RUNNING, String.valueOf(view.guard().getValue()));
            changes.clear();

            view.guard().setValue(view.guard().getItems().get(2));

            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, true),
                    new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, false),
                    new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, true)), changes);
            return null;
        });
    }

    @Test
    void autoBearNeedsAStartTimeAndClearTurnsEverythingOff() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);
            view.controller().onProfileLoad(profile(5L));
            assertTrue(view.auto().isDisabled());
            assertTrue(view.guard().isDisabled());

            view.start().setDraft(LocalDate.of(2026, 8, 1), "19", "00");
            view.start().commitSelection();
            assertFalse(view.auto().isDisabled());
            assertEquals(NO_NEW_RALLIES, String.valueOf(view.guard().getValue()));

            view.auto().setSelected(true);
            changes.clear();
            view.start().clearSelection();

            assertFalse(view.auto().isSelected());
            assertEquals(KEEP_RUNNING, String.valueOf(view.guard().getValue()));
            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "")));
            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, false)));
            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, false)));
            return null;
        });
    }

    @Test
    void recallNeedsJoinOrOwnRally() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            view.controller().onProfileLoad(liveProfile());
            assertFalse(view.recall().isDisabled());

            view.join().setSelected(false);
            assertTrue(view.recall().isDisabled());
            assertTrue(view.recall().isSelected(), "the stored choice is kept");
            return null;
        });
    }

    @Test
    void iconStartAndLeaderCannotBeTurnedOnTogether() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            view.controller().onProfileLoad(liveProfile());
            assertTrue(view.leader().isDisabled());
            Label blocked = view.get("labelLeaderBlocked");
            assertTrue(blocked.isVisible(), "the Leader card says why it cannot be picked");

            view.icon().setSelected(false);
            assertFalse(view.leader().isDisabled());
            view.leader().fire();
            assertTrue(view.icon().isDisabled());
            assertFalse(blocked.isVisible());

            view.controller().onProfileLoad(profile(9L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, "true")));
            assertFalse(view.icon().isDisabled(), "a saved pair must stay possible to undo");
            assertFalse(view.joiner().isDisabled(), "a saved pair must stay possible to undo");
            return null;
        });
    }

    @Test
    void clickingACardPicksItsRoleLightsItAndLeavesTheOtherEditable() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            view.controller().onProfileLoad(liveProfile());
            Node leaderSection = view.get("sectionLeader");
            Node joinerSection = view.get("sectionJoiner");
            Label leaderBadge = view.get("labelLeaderActive");
            Label joinerBadge = view.get("labelJoinerActive");

            assertFalse(isActive(leaderSection));
            assertTrue(isActive(joinerSection));
            assertEquals("ACTIVE", joinerBadge.getText());
            assertFalse(leaderBadge.isVisible());
            assertFalse(view.ownSquad().isDisabled(), "the dimmed Leader section stays editable");
            assertEquals(Integer.valueOf(1), view.ownSquad().getValue());
            assertTrue(view.ownSquad().getItems().contains(1), "a joiner's rally squad still shows its value");

            view.icon().setSelected(false);
            List<Change> changes = listen(view);
            click(view.ownSquad());
            assertFalse(view.leader().isSelected(), "using an input in a dimmed card does not switch role");
            assertTrue(view.<Node>get("headerLeader").getCursor() == javafx.scene.Cursor.HAND);
            click(leaderSection);
            assertTrue(view.leader().isSelected());
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, true)), changes);
            assertTrue(isActive(leaderSection));
            assertFalse(isActive(joinerSection));
            assertEquals("ACTIVE", leaderBadge.getText());
            assertFalse(joinerBadge.isVisible());
            assertTrue(view.join().isSelected(), "join settings stay on in both roles");
            assertFalse(view.hero().isDisabled());
            assertFalse(view.troop().isDisabled());
            return null;
        });
    }

    private static void click(Node target) {
        target.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null));
    }

    private static boolean isActive(Node section) {
        return section.getPseudoClassStates().stream().anyMatch(state -> "role-active".equals(state.getPseudoClassName()));
    }

    @Test
    void theRoleRoundTripsTheStoredOwnRallyValue() throws Exception {
        ProfileAux leader = profile(11L,
                config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3"),
                config(ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT, "8"),
                config(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, "true"));
        ProfileAux joiner = profile(12L,
                config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3"),
                config(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, "false"));
        noWritesOnLoad(leader);
        noWritesOnLoad(joiner);
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);

            view.controller().onProfileLoad(leader);
            assertTrue(view.leader().isSelected());
            assertFalse(view.joiner().isSelected());
            assertEquals("You start the rally on squad 8 and join others.", view.leaderSummary().getText());
            assertEquals("", view.joinerSummary().getText());
            renderWhenAsked(view, "bear-role-preview.png");

            view.controller().onProfileLoad(joiner);
            assertFalse(view.leader().isSelected());
            assertTrue(view.joiner().isSelected());
            assertEquals("You only join other rallies.", view.joinerSummary().getText());
            assertEquals("", view.leaderSummary().getText());
            renderWhenAsked(view, "bear-role-preview-joiner.png");

            view.controller().onProfileLoad(leader);
            assertTrue(view.leader().isSelected());
            assertTrue(changes.isEmpty(), "loading must save nothing, saw " + changes);

            view.joiner().fire();
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, false)), changes);
            view.joiner().fire();
            assertEquals(1, changes.size(), "picking the role already chosen must not save again");

            view.leader().fire();
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, false),
                    new Change(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, true)), changes);

            view.join().setSelected(false);
            assertEquals("You start the rally on squad 8.", view.leaderSummary().getText());
            return null;
        });
    }

    @Test
    void joinRalliesNeedsAHeroSquadAndTheLastOneCannotBeRemovedWhileJoining() throws Exception {
        AtomicReference<LoadedBearView> shared = new AtomicReference<>();
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            shared.set(view);
            view.controller().onProfileLoad(profile(3L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00")));
            view.hero().getCheckModel().clearChecks();
            return null;
        });
        settle();
        runOnFxThread(() -> {
            LoadedBearView view = shared.get();
            assertTrue(view.join().isDisabled());
            view.hero().getCheckModel().check(Integer.valueOf(2));
            return null;
        });
        settle();
        runOnFxThread(() -> {
            LoadedBearView view = shared.get();
            assertFalse(view.join().isDisabled());
            view.join().setSelected(true);
            view.hero().getCheckModel().clearCheck(Integer.valueOf(2));
            return null;
        });
        settle();
        runOnFxThread(() -> {
            assertEquals(List.of(2), shared.get().hero().getCheckModel().getCheckedItems());
            return null;
        });
    }

    @Test
    void aSquadPickedInOneGroupLeavesTheOthersChoices() throws Exception {
        AtomicReference<LoadedBearView> shared = new AtomicReference<>();
        AtomicReference<List<Change>> changes = new AtomicReference<>();
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            shared.set(view);
            changes.set(listen(view));
            view.controller().onProfileLoad(liveProfile());

            assertFalse(view.troop().getItems().contains(1));
            assertFalse(view.hero().getItems().contains(4));
            assertTrue(view.hero().getItems().contains(1), "a group keeps its own picks");
            view.hero().getCheckModel().check(Integer.valueOf(7));
            return null;
        });
        settle();
        runOnFxThread(() -> {
            LoadedBearView view = shared.get();
            assertFalse(view.troop().getItems().contains(7));
            assertEquals(List.of(4, 5, 6), view.troop().getCheckModel().getCheckedItems());
            assertEquals(List.of(1, 2, 3, 7), view.hero().getCheckModel().getCheckedItems());
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3,7")), changes.get(),
                    "rebuilding the lists must not save");
            view.icon().setSelected(false);
            view.ownSquad().setValue(1);
            view.leader().fire();
            return null;
        });
        settle();
        runOnFxThread(() -> {
            LoadedBearView view = shared.get();
            assertEquals(List.of(1, 2, 3), view.leaderHero().getCheckModel().getCheckedItems());
            assertFalse(List.of(1, 2, 3, 4, 5, 6).contains(view.ownSquad().getValue()),
                    "the leader must not rally with a squad it joins with");
            assertFalse(view.leaderHero().getItems().contains(view.ownSquad().getValue()));
            return null;
        });
    }

    @Test
    void aLeaderJoinsWithTheJoinerSquadsUntilItSavesItsOwn() throws Exception {
        ProfileAux unsaved = profile(21L,
                config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"),
                config(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT, "8"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING, "4,5,6"));
        ProfileAux saved = profile(22L,
                config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "10-11-2026 19:00"),
                config(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, "true"),
                config(ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT, "8"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3"),
                config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING, "4,5,6"),
                config(ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_FLAG_INT, "5"),
                config(ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_TROOP_FLAG_STRING, ""));
        noWritesOnLoad(unsaved);
        noWritesOnLoad(saved);
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = listen(view);

            view.controller().onProfileLoad(unsaved);
            assertEquals(List.of(1, 2, 3), view.leaderHero().getCheckModel().getCheckedItems());
            assertEquals(List.of(4, 5, 6), view.leaderTroop().getCheckModel().getCheckedItems());

            view.leaderHero().getCheckModel().check(Integer.valueOf(7));
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_FLAG_INT, "1,2,3,7"),
                    new Change(ConfigurationKeyEnum.BEAR_TRAP_LEADER_JOIN_TROOP_FLAG_STRING, "4,5,6")), changes,
                    "a leader edit saves both leader lists and never the joiner lists");
            assertEquals(List.of(1, 2, 3), view.hero().getCheckModel().getCheckedItems());

            view.controller().onProfileLoad(saved);
            assertEquals(List.of(5), view.leaderHero().getCheckModel().getCheckedItems());
            assertEquals(List.of(), view.leaderTroop().getCheckModel().getCheckedItems(),
                    "no troop squads is a real leader choice");
            assertEquals(List.of(1, 2, 3), view.hero().getCheckModel().getCheckedItems());
            assertEquals(List.of(4, 5, 6), view.troop().getCheckModel().getCheckedItems());
            assertEquals(Integer.valueOf(8), view.ownSquad().getValue());
            return null;
        });
    }

    @Test
    void restoresBearJoinFormationsByValueInsteadOfListIndex() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(7L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "2,3,4"));

            view.controller().onProfileLoad(profile);

            assertEquals(List.of(2, 3, 4), view.hero().getCheckModel().getCheckedItems());
            assertTrue(changes.isEmpty(), "restoring saved formations must not rewrite the profile");

            view.hero().getCheckModel().clearCheck(Integer.valueOf(2));
            view.hero().getCheckModel().check(Integer.valueOf(7));

            assertEquals(new Change(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "3,4,7"),
                    changes.get(changes.size() - 1));
            return null;
        });
    }

    /** Saves the themed page as a PNG when -Dbear.render.dir is set, for reviewing the layout by eye. */
    private static void renderWhenAsked(LoadedBearView view, String fileName) throws Exception {
        String renderDir = System.getProperty("bear.render.dir");
        if (renderDir == null) {
            return;
        }
        // A scene that was already snapshotted can return its earlier image, so each render gets a new one.
        if (view.root().getScene() != null) {
            view.root().getScene().setRoot(new Group());
        }
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        Scene scene = new Scene(view.root(), 900, 1060);
        scene.getStylesheets().add(BearTrapLayoutController.class.getResource("/styles/style.css").toExternalForm());
        view.root().applyCss();
        view.root().layout();
        File out = Path.of(renderDir, fileName).toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", out);
    }

    @SuppressWarnings("unchecked")
    private static LoadedBearView loadBearView() throws Exception {
        FXMLLoader loader = new FXMLLoader(BearTrapLayoutController.class.getResource("/layout/BearTrapLayout.fxml"));
        BearTrapLayoutController controller = new BearTrapLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        Map<String, Object> namespace = loader.getNamespace();
        return new LoadedBearView(root, controller, namespace);
    }

    private static ProfileAux profile(long id, ConfigAux... configs) {
        ProfileAux profile = new ProfileAux(id, "Profile " + id, "1", true, 50L, "", 0L);
        profile.setConfigs(List.of(configs));
        return profile;
    }

    private static ConfigAux config(ConfigurationKeyEnum key, String value) {
        return new ConfigAux(key.name(), value);
    }

    private static <T> T runOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "JavaFX action timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private record Change(ConfigurationKeyEnum key, Object value) {
    }

    private static final class TestProfileController extends AbstractProfileController {
        private void register(TextField field, ConfigurationKeyEnum key) {
            registerTextField(field, key);
        }

        private void startListening() {
            initializeChangeEvents();
        }
    }

    private record LoadedBearView(Parent root, BearTrapLayoutController controller, Map<String, Object> namespace) {
        @SuppressWarnings("unchecked")
        private <T> T get(String id) {
            return (T) namespace.get(id);
        }

        private UtcDateTimeEditor start() {
            return get("timerDateTimeEditor");
        }

        private ComboBox<Object> guard() {
            return get("comboBoxGuard");
        }

        private CheckBox auto() {
            return get("checkBoxEnableBearTrap");
        }

        private CheckBox icon() {
            return get("checkBoxIconStart");
        }

        private CheckBox join() {
            return get("checkBoxEnableJoin");
        }

        private RadioButton leader() {
            return get("radioLeader");
        }

        private RadioButton joiner() {
            return get("radioJoiner");
        }

        private Label leaderSummary() {
            return get("labelLeaderSummary");
        }

        private Label joinerSummary() {
            return get("labelJoinerSummary");
        }

        private CheckComboBox<Integer> leaderHero() {
            return get("checkComboBoxLeaderJoinFlag");
        }

        private CheckComboBox<Integer> leaderTroop() {
            return get("checkComboBoxLeaderTroopFlag");
        }

        private CheckBox recall() {
            return get("checkBoxRecallTroops");
        }

        private CheckComboBox<Integer> hero() {
            return get("checkComboBoxJoinFlag");
        }

        private CheckComboBox<Integer> troop() {
            return get("checkComboBoxTroopFlag");
        }

        private ComboBox<Integer> ownSquad() {
            return get("comboBoxRallyFlag");
        }

        private TextField lead() {
            return get("textFieldPreparationTime");
        }
    }
}
