package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.PrimerDark;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.engine.squads.HeroGearStore;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** The Heroes page must fill from the saved files, follow the design brief, and its options must change the list. */
class HeroesLayoutUxTest {

    @TempDir
    Path workspace;

    private String previousWorkspace;
    private boolean workspaceSaved;

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
        onFx(() -> {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            return null;
        });
    }

    @AfterEach
    void restoreWorkspace() {
        if (!workspaceSaved) return; // a test that never seeded left the setting alone, so there is nothing to put back
        if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
    }

    // ── What the page shows ──────────────────────────────────────────

    @Test
    void fillsGearPlanLevelsJoinersAndRoster() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)));

        assertEquals(5, view.rows("tableGear").size());
        assertEquals(3, view.rows("tableUpgrades").size());
        assertEquals(5, view.rows("tableLevels").size());
        assertEquals(List.of("Bahiti", "Boots", "42%", "44%", "10 stones", "10 stones", "Short by 7 stones"), view.rows("tableUpgrades").getFirst());

        List<List<String>> joiners = view.rows("tableJoiners");
        assertFalse(joiners.isEmpty(), "joiners ranked");
        assertEquals("1", joiners.getFirst().get(0));
        assertEquals(7, joiners.getFirst().size());
        for (int i = 1; i < joiners.size(); i++) {
            assertTrue(Double.parseDouble(joiners.get(i - 1).get(6)) >= Double.parseDouble(joiners.get(i).get(6)), joiners.toString());
        }
        assertTrue(view.label("labelJoiners").getText().startsWith("Last scan"), view.label("labelJoiners").getText());

        List<List<String>> roster = view.rows("tableHeroes");
        assertEquals(21, roster.size());
        assertEquals(6, roster.getFirst().size());
        assertEquals("SSR", roster.getFirst().get(1));
        assertTrue(view.label("labelRoster").getText().contains("21 heroes"), view.label("labelRoster").getText());
        assertEquals(List.of("Hero Gear", "Hero Level Ups", "Rally Joiners", "Roster"), view.tabTitles());
    }

    @Test
    void theGearTableShowsEachHerosWeakestMainPieceAndExpandsToEveryPiece() throws Exception {
        View view = onFx(this::load);
        Hyperlink link = (Hyperlink) view.namespace().get("linkGear");

        assertEquals(List.of("Mia", "Flint", "Bahiti", "Sergey", "Molly"),
                view.rows("tableGear").stream().map(r -> r.getFirst()).toList());
        assertEquals(List.of("Mia", "Boots", "Main piece", "B", "81", "50%"), view.rows("tableGear").getFirst());
        assertEquals("Show all 20 pieces", link.getText());
        assertTrue(Tooltip_text(view).startsWith("Grades: A is nearly maxed"), Tooltip_text(view));

        onFx(() -> {
            link.fire();
            return null;
        });

        List<List<String>> rows = view.rows("tableGear");
        assertEquals(20, rows.size());
        assertEquals("Show fewer", link.getText());
        assertEquals(List.of("Mia", "Goggles", "Main piece", "A", "100", "60%"), rows.getFirst());
        assertEquals(List.of("Mia", "Gauntlets", "Spare", "B", "55", "10%"), rows.get(2));
        assertEquals(List.of("Hero", "Piece", "Used for", "Grade", "Level", "Mastery"), view.headers("tableGear"));
        for (List<String> row : rows) {
            for (String cell : row) {
                assertFalse(cell.contains("*") || cell.contains("+") || cell.contains("...") || cell.contains("null"), row.toString());
            }
        }
    }

    private static String Tooltip_text(View view) throws Exception {
        return onFx(() -> {
            Object installed = view.label("labelGearHelp").getProperties().get("javafx.scene.control.Tooltip");
            return installed == null ? "" : ((javafx.scene.control.Tooltip) installed).getText();
        });
    }

    @Test
    void numberColumnsAreRightAlignedAndNamesAreNeverCut() throws Exception {
        View view = onFx(this::load);

        for (String id : List.of("tableGear", "tableUpgrades", "tableLevels", "tableJoiners", "tableHeroes")) {
            for (TableColumn<List<String>, ?> column : view.table(id).getColumns()) {
                boolean number = column.getStyleClass().contains("hero-number");
                assertEquals(number, column.getUserData().toString().equals("NUMBER"), id + " " + column.getId());
            }
        }
        assertEquals(List.of("Cost", "Running total"), view.headers("tableUpgrades").subList(4, 6));
        view.table("tableHeroes").getColumns().stream().filter(c -> c.getId().equals("Hero")).forEach(c ->
                assertTrue(c.getMinWidth() >= 84, "a hero name column is as wide as its longest name: " + c.getMinWidth()));
    }

    @Test
    void theHeaderIsThreeShortLinesOnLastNightAndTonight() throws Exception {
        View view = onFx(this::load);
        assertEquals("Off", view.label("labelStatusLine").getText());
        assertEquals("Gear not checked yet", view.label("labelLastLine").getText());
        assertEquals("", view.label("labelTonightLine").getText());
        assertFalse(view.label("labelTonightLine").isVisible(), "an off page has no third line: the reason sits under Run now");

        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_OFF, HeroesLayoutController.FORGE_PRACTICE));
        assertEquals("On \u00b7 Practice", view.label("labelStatusLine").getText());
        assertEquals("Has not run yet", view.label("labelLastLine").getText());
        assertTrue(view.label("labelTonightLine").getText().matches(
                "(Tonight \\d\\d:\\d\\d|Next run (Today|Tomorrow) \\d\\d:\\d\\d): spends nothing"), view.label("labelTonightLine").getText());

        Files.writeString(squads().resolve("hero-gear-night-1.json"), "{\"startedAt\": \"" + Instant.now().minusSeconds(7200)
                + "\", \"slot\": \"2026-11-12T02:30:00Z\", \"buys\": 0, \"levels\": 0, \"finished\": true}");
        View ran = onFx(this::open);
        onFx(() -> ran.tick(true, HeroesLayoutController.ENHANCE_OFF, HeroesLayoutController.FORGE_PRACTICE));
        assertTrue(ran.label("labelLastLine").getText().matches("Last (night|run) \\(practice\\) (\\d\\d:\\d\\d|.+): nothing to change"),
                ran.label("labelLastLine").getText());

        onFx(() -> ran.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_REAL));
        assertEquals("On \u00b7 Spending", ran.label("labelStatusLine").getText());
        assertTrue(ran.label("labelLastLine").getText().endsWith(": raised 0 levels, forged 0"), ran.label("labelLastLine").getText());
        assertTrue(ran.label("labelTonightLine").getText().endsWith("every XP item you have \u00b7 no forge: needs 10 stones, you have 3"),
                ran.label("labelTonightLine").getText());
        for (String id : List.of("labelStatusLine", "labelLastLine", "labelTonightLine")) {
            assertTrue(ran.label(id).getText().length() <= 90, ran.label(id).getText());
        }
    }

    @Test
    void withEnoughStonesTheNextNightSaysHowManyForgesItCanAfford() throws Exception {
        seed(true);
        editInventory("\"essenceStones\": 3", "\"essenceStones\": 25");
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, "Rally squads")));

        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_OFF, HeroesLayoutController.FORGE_REAL));

        assertTrue(view.label("labelTonightLine").getText().endsWith("forge up to 2 (25 stones)"), view.label("labelTonightLine").getText());

        onFx(() -> {
            view.<String>combo("comboBoxFocus").setValue("Weakest first");
            return null;
        });
        assertTrue(view.label("labelTonightLine").getText().endsWith("no forge: needs 40 stones, you have 25"),
                "the weakest piece costs 40, so nothing is bought: " + view.label("labelTonightLine").getText());
    }

    @Test
    void whilePausedTheHeaderSaysSoAndThereIsNoRunNow() throws Exception {
        seed(true);
        new HeroGearStore(workspace).saveHalt(1, new HeroGearStore.GearHalt("Enhance: no read",
                Instant.now().minusSeconds(7200).toString(), HeroGearStore.GearHalt.ENHANCE));

        View view = onFx(this::open);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_PRACTICE, HeroesLayoutController.FORGE_PRACTICE));

        assertEquals("Paused", view.label("labelStatusLine").getText());
        assertFalse(view.label("labelTonightLine").isVisible());
        assertTrue(view.label("labelLastLine").getText().startsWith("Stopped "), view.label("labelLastLine").getText());
        Button run = (Button) view.namespace().get("buttonRunNow");
        assertFalse(run.isVisible());
        assertFalse(run.isManaged());
        assertFalse(view.label("labelRunReason").isVisible(), "no second 'resume first' line: the banner has the button");
    }

    // ── Status banner ────────────────────────────────────────────────

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Instant THURSDAY_TEN = Instant.parse("2026-11-12T15:00:00Z");

    // Wednesday night's run, check and stone count all agree: 21:31 local, a few hours before Thursday 10:00.
    private void seedPinnedDay() throws Exception {
        seed(true);
        String ran = "2026-11-12T02:31:00Z";
        editInventory("2026-11-10T23:32:00Z", ran);
        editGear("\"heroWeights\"", "\"scannedAt\": \"" + ran + "\", \"heroWeights\"");
        Files.writeString(squads().resolve("hero-gear-night-1.json"), "{\"startedAt\": \"" + ran
                + "\", \"slot\": \"2026-11-12T02:30:00Z\", \"buys\": 0, \"levels\": 0, \"finished\": true, \"mode\": \"practice\","
                + " \"plan\": [\"raise Molly Boots to Lv.70\", \"skip forge (3 of 10 stones)\"]}");
    }

    @Test
    void onePinnedMomentMakesEveryTimeOnThePageAgree() throws Exception {
        seedPinnedDay();
        View view = onFx(() -> openPinned(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true)));

        assertEquals("On \u00b7 Practice", view.label("labelStatusLine").getText());
        assertEquals("Last night (practice) 21:31: would raise Molly's Boots to 70, would skip forge (3 of 10 stones)",
                view.label("labelLastLine").getText());
        assertEquals("Tonight 21:30: spends nothing", view.label("labelTonightLine").getText());
        assertEquals("Checked Yesterday 21:31", view.label("labelGearInfo").getText());
        assertEquals("You have 3 stones, counted Yesterday 21:31", view.label("labelStones").getText());
        assertTrue(view.label("labelXp").getText().contains("read Yesterday 21:31"), view.label("labelXp").getText());
        assertEquals("21:30", view.combo("comboBoxRunAt").getValue(), "the run time is the local one");
        assertEquals("02:30 UTC", view.<String>combo("comboBoxRunAt").getTooltip().getText());
    }

    @Test
    void afterTonightsTimeTheHeaderSaysNextRunTomorrow() throws Exception {
        seedPinnedDay();
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true), Instant.parse("2026-11-13T03:00:00Z")));

        assertEquals("Next run Tomorrow 21:30: spends nothing", view.label("labelTonightLine").getText());
    }

    @Test
    void planLinesReadInPlainWords() {
        assertEquals("raise Molly's Boots to 70", HeroesLayoutController.planText("raise Molly Boots to Lv.70"));
        assertEquals("forge Bahiti's Boots 42\u219244%", HeroesLayoutController.planText("forge Bahiti Boots 42% to 44% (10 stones)"));
        assertEquals("raise Ling Xue's Goggles to 51", HeroesLayoutController.planText("raise Ling Xue Goggles to Lv.51"));
        assertEquals("something else", HeroesLayoutController.planText("something else"));
        assertEquals("skip forge (3 of 10 stones)", HeroesLayoutController.planText("skip forge (3 of 10 stones)"));
    }

    @Test
    void theNextRunIsTodayUntilItsTimeHasPassedAndThenTomorrow() {
        String[][] cases = {
            {"2026-11-12T01:00:00Z", "Today 21:30"},      // Wed 20:00 local
            {"2026-11-12T15:00:00Z", "Today 21:30"},      // Thu 10:00
            {"2026-11-13T01:29:00Z", "Today 21:30"},      // Thu 20:29
            {"2026-11-13T02:31:00Z", "Tomorrow 21:30"},   // Thu 21:31, tonight's has just passed
            {"2026-11-13T03:00:00Z", "Tomorrow 21:30"},   // Thu 22:00
        };
        for (String[] c : cases) {
            Instant now = Instant.parse(c[0]);
            assertEquals(c[1], HeroesLayoutController.friendly(HeroesLayoutController.nextRun("02:30", now, NEW_YORK).toInstant(), now, NEW_YORK), c[0]);
        }
    }

    @Test
    void aHaltDuringTonightsRunPausesTheMasterAndTheBannerSaysWhereThePieceStands() throws Exception {
        seedPinnedDay();
        Files.writeString(squads().resolve("hero-gear-night-1.json"), "{\"startedAt\": \"2026-11-12T02:31:00Z\", \"slot\": \"2026-11-12T02:30:00Z\","
                + " \"buys\": 0, \"levels\": 1, \"finished\": false, \"mode\": \"live\"}");
        new HeroGearStore(workspace).saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level",
                "2026-11-12T02:33:00Z", HeroGearStore.GearHalt.ENHANCE));
        View view = onFx(() -> openPinned(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true,
                ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, true, ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, true)));

        assertEquals("Paused", view.label("labelStatusLine").getText());
        assertEquals("Stopped Yesterday 21:33", view.label("labelLastLine").getText());
        assertFalse(view.label("labelTonightLine").isVisible(), "the stop is said once in the header and once in the banner");
        assertEquals("Off (paused)", view.toggle("switchNightly").getText());
        assertTrue(view.toggle("switchNightly").isDisabled());
        assertTrue(view.toggle("switchNightly").getStyleClass().contains("paused"), "it never wears the lit teal On");
        assertEquals("Locked while paused. Use Resume upgrades above to change these.", view.label("labelNightlyCaption").getText());
        assertEquals("It stopped to be safe while raising gear levels: Mia Boots showed no new level (still Lv.81). "
                + "It may stop again if that is still wrong.", view.label("labelHalt").getText());
        assertFalse(view.label("labelHalt").getText().contains("21:33"), "the time is in the header only");
        for (String id : List.of("segmentEnhance", "segmentForge", "segmentEnhanceAmount", "segmentForgeAmount", "comboBoxReserve",
                "comboBoxRunAt", "comboBoxFocus", "switchQuickEquip", "gridHeroes")) {
            assertTrue(view.disabled(id), id + " is locked while paused, so the caption is true");
        }
        assertEquals("Locked while paused.", view.label("labelEnhanceReason").getText());
        assertEquals("Locked while paused.", view.label("labelForgeReason").getText());

        onFx(() -> {
            ((Button) view.namespace().get("buttonClearHalt")).fire();
            return null;
        });

        assertFalse(view.toggle("switchNightly").isDisabled());
        assertFalse(view.disabled("segmentForge"));
        assertFalse(view.disabled("comboBoxRunAt"));
        assertTrue(view.disabled("segmentForgeAmount"), "still greyed: Forge is on Practice");
        assertEquals("Used when Forge mastery is For real.", view.label("labelForgeReason").getText());
        assertEquals("On", view.toggle("switchNightly").getText());
        assertFalse(view.toggle("switchNightly").getStyleClass().contains("paused"));
        assertTrue(((Button) view.namespace().get("buttonRunNow")).isVisible());
        assertEquals("Checks your gear, then raises and forges what you allow below.", view.label("labelNightlyCaption").getText());
    }

    @Test
    void withNoHaltThereIsNoBannerAndNoResumeButton() throws Exception {
        View view = onFx(this::load);

        HBox banner = (HBox) view.namespace().get("boxHalt");
        assertFalse(banner.isVisible());
        assertFalse(banner.isManaged(), "it takes no room on the page");
        assertFalse(view.label("labelStatusLine").getText().toLowerCase().contains("not halted"));
    }

    @Test
    void aSavedHaltShowsATitleAReasonAndOneResumeThatLiftsIt() throws Exception {
        seed(true);
        HeroGearStore store = new HeroGearStore(workspace);
        store.saveHalt(1, new HeroGearStore.GearHalt("Quick Equip: Mia slot 1 went down", "2026-11-10T07:45:00Z",
                HeroGearStore.GearHalt.QUICK_EQUIP));
        store.saveLeadBaseline(1, Map.of("Mia", List.of()));

        View view = onFx(this::open);
        Button resume = (Button) view.namespace().get("buttonClearHalt");
        HBox banner = (HBox) view.namespace().get("boxHalt");

        assertTrue(banner.isVisible());
        assertEquals("Resume upgrades", resume.getText());
        assertTrue(view.texts(banner).contains("Gear upgrades are paused"), view.texts(banner).toString());
        String reason = view.label("labelHalt").getText();
        assertTrue(reason.contains("to be safe while wearing your best spare gear: Mia slot 1 went down."), reason);
        assertTrue(reason.endsWith("It may stop again if that is still wrong."), reason);
        assertFalse(resume.isDisabled());

        onFx(() -> {
            resume.fire();
            return null;
        });

        assertTrue(store.loadHalt(1).isEmpty());
        assertTrue(store.loadLeadBaseline(1).isEmpty());
        assertFalse(banner.isVisible());
    }

    @Test
    void anEnhanceHaltNamesRaisingGearLevelsInWords() throws Exception {
        seed(true);
        new HeroGearStore(workspace).saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level",
                "2026-11-10T07:45:00Z", HeroGearStore.GearHalt.ENHANCE));

        View view = onFx(this::open);

        assertTrue(view.label("labelHalt").getText().contains("to be safe while raising gear levels: Mia Boots showed no new level (still Lv.81)."),
                view.label("labelHalt").getText());
    }

    @Test
    void theHaltRefreshesWhileTheHeroGearTabIsShowingAndStopsOtherwise() throws Exception {
        seed(true);
        HeroGearStore store = new HeroGearStore(workspace);
        View view = onFx(this::open);
        Timeline refresh = view.controller().haltRefresh();
        assertEquals(Animation.Status.STOPPED, refresh.getStatus(), "not on screen yet");

        Scene scene = onFx(() -> new Scene(view.root(), 1100, 800));
        assertEquals(Animation.Status.RUNNING, onFx(refresh::getStatus));
        assertFalse(((HBox) view.namespace().get("boxHalt")).isVisible());

        store.saveHalt(1, new HeroGearStore.GearHalt("Quick Equip: no read", "2026-11-10T07:45:00Z", HeroGearStore.GearHalt.QUICK_EQUIP));
        onFx(() -> {
            refresh.getKeyFrames().getFirst().getOnFinished().handle(null);
            return null;
        });
        assertTrue(((HBox) view.namespace().get("boxHalt")).isVisible());
        assertTrue(view.label("labelHalt").getText().contains("wearing your best spare gear: no read"), view.label("labelHalt").getText());

        TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
        onFx(() -> {
            tabs.getSelectionModel().select(1);
            return null;
        });
        assertEquals(Animation.Status.STOPPED, onFx(refresh::getStatus));
        onFx(() -> {
            tabs.getSelectionModel().select(0);
            return null;
        });
        assertEquals(Animation.Status.RUNNING, onFx(refresh::getStatus));
        onFx(() -> {
            scene.setRoot(new javafx.scene.layout.Pane());
            return null;
        });
        assertEquals(Animation.Status.STOPPED, onFx(refresh::getStatus));
    }

    @Test
    void anUnreadableHaltStillShowsTheBannerWithResume() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-gear-halt-1.json"), "{not json");

        View view = onFx(this::open);

        assertTrue(((HBox) view.namespace().get("boxHalt")).isVisible());
        assertTrue(view.label("labelHalt").getText().startsWith("Could not read the pause state"), view.label("labelHalt").getText());
        assertFalse(((Button) view.namespace().get("buttonClearHalt")).isDisabled());
    }

    // ── Settings ─────────────────────────────────────────────────────

    @Test
    void everySwitchSaysOnOrOffAndWritesItsSetting() throws Exception {
        View view = onFx(this::load);

        for (String id : List.of("switchNightly", "switchQuickEquip", "switchPlan")) {
            ToggleSwitch toggle = view.toggle(id);
            assertFalse(toggle.isSelected(), id);
            assertEquals("Off", toggle.getText(), id);
        }
        onFx(() -> {
            view.toggle("switchNightly").setSelected(true);
            view.toggle("switchQuickEquip").setSelected(true);
            view.toggle("switchPlan").setSelected(true);
            return null;
        });

        assertEquals("On", view.toggle("switchNightly").getText());
        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true),
                Map.entry(ConfigurationKeyEnum.HERO_QUICK_EQUIP_ENABLED_BOOL, true),
                Map.entry(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)), view.published);
    }

    @Test
    void savedSwitchesLoadWithoutWritingAnythingBack() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true,
                ConfigurationKeyEnum.HERO_QUICK_EQUIP_ENABLED_BOOL, true)));

        assertTrue(view.toggle("switchNightly").isSelected());
        assertTrue(view.toggle("switchQuickEquip").isSelected());
        assertEquals("On", view.toggle("switchQuickEquip").getText());
        assertEquals(List.of(), view.published);
    }

    @Test
    void theSettingsBelowTheMasterAreHiddenWhileItIsOff() throws Exception {
        View view = onFx(this::load);

        VBox below = (VBox) view.namespace().get("boxNightly");
        assertFalse(below.isVisible());
        assertFalse(below.isManaged());
        onFx(() -> {
            view.toggle("switchNightly").setSelected(true);
            return null;
        });
        assertTrue(below.isVisible());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRunTimeIsALocalTimeControlInFifteenMinuteStepsWithUtcInItsTooltip() throws Exception {
        View view = onFx(this::load);
        ComboBox<String> runAt = (ComboBox<String>) view.namespace().get("comboBoxRunAt");

        assertEquals(96, runAt.getItems().size());
        assertEquals(HeroesLayoutController.localTime("02:30"), runAt.getValue(), "the saved UTC slot shows as this PC's clock reads it");
        assertEquals("02:30 UTC", runAt.getTooltip().getText());

        onFx(() -> {
            runAt.setValue("07:15");
            return null;
        });
        String utc = HeroesLayoutController.toUtc("07:15", ZoneId.systemDefault());
        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_TIME_UTC_STRING, utc)), view.published);
        assertEquals(utc + " UTC", runAt.getTooltip().getText());
        assertEquals("Your local time, every night. It checks your gear first.", view.texts((Parent) view.namespace().get("boxNightly")).stream()
                .filter(t -> t.startsWith("Your local")).findFirst().orElseThrow());
    }

    @Test
    void localAndUtcTimesConvertBothWaysAcrossZones() {
        ZoneId newYork = ZoneId.of("America/New_York");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");

        assertEquals("02:30", HeroesLayoutController.toUtc("21:30", ZoneId.of("UTC-05:00")));
        assertEquals("17:00", HeroesLayoutController.toUtc("02:00", tokyo));
        assertEquals(HeroesLayoutController.toUtc("12:00", newYork).length(), 5);
    }

    @Test
    void thereIsNoScanFrequencyToPickBecauseTheCheckAlwaysRunsFirst() throws Exception {
        View view = onFx(this::load);

        assertFalse(view.namespace().containsKey("comboBoxScanDays"));
        assertFalse(view.namespace().containsKey("comboBoxEnhanceLevels"), "no pointless count");
        assertFalse(view.namespace().containsKey("comboBoxMaxBuys"), "no pointless count");
    }

    @Test
    void theHeroesAreWhoeverTheScanReadAndEachCanBeLeftOut() throws Exception {
        View view = onFx(this::load);

        assertEquals(List.of("Mia", "Flint", "Bahiti", "Sergey", "Molly"), view.choices().stream().map(CheckBox::getText).toList());
        assertTrue(view.choices().stream().allMatch(CheckBox::isSelected));
        assertEquals(List.of(0, 1, 2, 0, 1), view.choices().stream().map(GridPane::getColumnIndex).toList(), "three columns");
        assertEquals("Your 5 strongest, read at each gear check. Untick to leave one out.", view.label("labelHeroesInfo").getText());
        assertFalse(((VBox) view.namespace().get("boxHeroesEmpty")).isVisible());

        onFx(() -> {
            view.choice("Bahiti").setSelected(false);
            return null;
        });

        assertTrue(view.rows("tableUpgrades").stream().noneMatch(r -> r.getFirst().equals("Bahiti")), view.rows("tableUpgrades").toString());
        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_SKIP_HEROES_STRING, "Bahiti")), view.published);
    }

    @Test
    void aSavedLeaveOutListLoadsAndAnotherAccountsHeroesNeedNoCode() throws Exception {
        seed(true);
        editGear("Bahiti", "Geronimo");
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_SKIP_HEROES_STRING, "Mia")));

        assertEquals(List.of("Mia", "Flint", "Geronimo", "Sergey", "Molly"), view.choices().stream().map(CheckBox::getText).toList());
        assertFalse(view.choice("Mia").isSelected());
        assertTrue(view.choice("Geronimo").isSelected());
        assertTrue(view.rows("tableUpgrades").stream().noneMatch(r -> r.getFirst().equals("Mia")));
    }

    @Test
    void withNoScanTheHeroesShowAnEmptyStateNotNames() throws Exception {
        View view = onFx(() -> {
            seed(false);
            Files.delete(squads().resolve("hero-gear-1.json"));
            return open();
        });

        assertTrue(view.choices().isEmpty());
        assertTrue(((VBox) view.namespace().get("boxHeroesEmpty")).isVisible());
        assertEquals(List.of("No heroes read yet", "They appear after the first gear check."),
                view.texts((VBox) view.namespace().get("boxHeroesEmpty")));
        assertTrue(((VBox) view.namespace().get("boxGearEmpty")).isVisible());
        assertFalse(view.table("tableGear").isVisible(), "a table with no rows shows the empty state, not bare headers");
        assertTrue(((VBox) view.namespace().get("boxUpgradesEmpty")).isVisible());
    }

    @Test
    void raisingLevelsIsOneSegmentedChoiceThatWritesItsTwoSettings() throws Exception {
        View view = onFx(this::load);
        Segmented mode = view.segment("segmentEnhance");

        assertEquals(List.of("Off", "Practice", "For real"), mode.getItems());
        assertEquals("Off", mode.getValue());
        assertEquals("All", view.segment("segmentEnhanceAmount").getValue(), "the default spends every XP item");

        onFx(() -> {
            mode.setValue(HeroesLayoutController.ENHANCE_PRACTICE);
            mode.setValue(HeroesLayoutController.ENHANCE_REAL);
            mode.setValue(HeroesLayoutController.ENHANCE_OFF);
            return null;
        });
        assertEquals(List.of(
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, true),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, false),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, true),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, true),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, false),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, false)), view.published);
    }

    @Test
    void forgeStartsOnPracticeAndTurningOnSpendingAsksFirst() throws Exception {
        View view = onFx(this::load);
        view.answer().set(false);
        Segmented mode = view.segment("segmentForge");
        assertEquals("Practice", mode.getValue());

        onFx(() -> {
            mode.setValue(HeroesLayoutController.FORGE_REAL);
            return null;
        });

        assertEquals(List.of("Forge will spend Essence Stones every night: every forge your stones allow, always leaving 0 stones unspent. Turn spending on?"),
                view.asked());
        assertEquals("Practice", mode.getValue(), "a second thought falls back to practice");
        assertTrue(mode.button("Practice").isSelected());
        assertEquals(List.of(), view.published, "nothing is saved");

        view.answer().set(true);
        onFx(() -> {
            mode.setValue(HeroesLayoutController.FORGE_REAL);
            return null;
        });
        assertEquals("For real", mode.getValue());
        assertTrue(mode.button("For real").isSelected());
        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, true)), view.published);
    }

    @Test
    void goingBackToPracticeAndLoadingASavedSpendModeNeverAsk() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, true)));
        assertEquals(List.of(), view.asked());

        onFx(() -> {
            view.segment("segmentForge").setValue(HeroesLayoutController.FORGE_PRACTICE);
            return null;
        });

        assertEquals(List.of(), view.asked());
        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, false)), view.published);
    }

    @Test
    void onlyTheSpendingChoiceIsRedAndEveryChoiceCarriesTheNeverPressedHint() throws Exception {
        View view = onFx(this::load);

        for (String id : List.of("segmentEnhance", "segmentForge")) {
            Segmented segment = view.segment(id);
            assertTrue(segment.button("For real").getStyleClass().contains("segment-danger"), id);
            assertFalse(segment.button("Practice").getStyleClass().contains("segment-danger"), id);
            assertEquals("Never pressed: Reforge, Ascend, Redeem, or anything that costs gems.",
                    segment.button("For real").getTooltip().getText(), id);
        }
        assertFalse(view.namespace().containsKey("labelForgePill"), "no second label repeats the choice");
        assertFalse(view.texts((Parent) view.namespace().get("boxNightly")).stream().anyMatch(t -> t.startsWith("Never pressed")),
                "the hint is a tooltip, not a paragraph");

        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_REAL));
        assertTrue(view.segment("segmentEnhance").button("For real").isSelected());
        assertTrue(view.segment("segmentForge").button("For real").isSelected());
    }

    @Test
    void savedModesLoadAsTheMatchingChoices() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(
                ConfigurationKeyEnum.HERO_GEAR_ENHANCE_ENABLED_BOOL, true,
                ConfigurationKeyEnum.HERO_GEAR_ENHANCE_LIVE_BOOL, true,
                ConfigurationKeyEnum.HERO_GEAR_FORGE_LIVE_BOOL, true)));

        assertEquals("For real", view.segment("segmentEnhance").getValue());
        assertEquals("For real", view.segment("segmentForge").getValue());
        assertEquals(List.of(), view.published, "loading a profile writes nothing back");
    }

    @Test
    void aGreyedRowSaysWhyBesideItAndTheReasonGoesWhenItApplies() throws Exception {
        View view = onFx(this::load);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_PRACTICE, HeroesLayoutController.FORGE_PRACTICE));

        for (String control : List.of("segmentEnhanceAmount", "segmentForgeAmount", "comboBoxReserve")) assertTrue(view.disabled(control), control);
        assertEquals("Used when Raise gear levels is For real.", view.label("labelEnhanceReason").getText());
        assertEquals("Used when Forge mastery is For real.", view.label("labelForgeReason").getText());
        for (String reason : List.of("labelEnhanceReason", "labelForgeReason", "labelReserveReason")) {
            assertTrue(view.label(reason).isVisible(), reason);
        }

        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_REAL));
        for (String control : List.of("segmentEnhanceAmount", "segmentForgeAmount", "comboBoxReserve")) assertFalse(view.disabled(control), control);
        for (String reason : List.of("labelEnhanceReason", "labelForgeReason", "labelReserveReason")) {
            assertFalse(view.label(reason).isVisible(), reason);
        }
        assertFalse(view.disabled("comboBoxFocus"), "the order still shapes the practice list");
        for (String row : List.of("rowEnhanceAmount", "rowForgeAmount", "rowReserve")) {
            assertFalse(view.disabled(row), "the label and its reason are not greyed with the control: " + row);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void gearOrderOffersBestReturnFirstAndTheOthersInAlphabeticalOrder() throws Exception {
        View view = onFx(this::load);
        ComboBox<String> order = (ComboBox<String>) view.namespace().get("comboBoxFocus");

        assertEquals(List.of("Best return per stone", "Rally leaders first, then weakest", "Weakest first"), order.getItems());
        assertEquals("Best return per stone", order.getValue(), "best return per stone is the default, not the lowest piece");
        assertEquals("Bahiti", view.rows("tableUpgrades").getFirst().getFirst(), "the hero who fights most goes first");
        assertTrue(view.rows("tableUpgrades").stream().noneMatch(r -> r.getFirst().equals("Molly")), "Molly is weighted 0 and is skipped");
    }

    @Test
    void anOldOrderSettingLoadsAsTheNewChoiceWithoutBeingRewritten() throws Exception {
        seed(true);
        View rally = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, "Rally squads")));
        View balanced = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, "Balanced")));

        assertEquals("Best return per stone", rally.<String>combo("comboBoxFocus").getValue());
        assertEquals("Bahiti", rally.rows("tableUpgrades").getFirst().getFirst(), "the old default ranked by squad weight");
        assertEquals("Best return per stone", balanced.<String>combo("comboBoxFocus").getValue());
        assertEquals(List.of(), rally.published, "loading writes nothing back");
        assertEquals(List.of(), balanced.published);
    }

    @Test
    void choosingAnOrderSavesIt() throws Exception {
        View view = onFx(this::load);

        onFx(() -> {
            view.<String>combo("comboBoxFocus").setValue("Rally leaders first, then weakest");
            return null;
        });

        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, "Rally leaders first, then weakest")), view.published);
        assertEquals("Bahiti", view.rows("tableUpgrades").getFirst().getFirst());
    }

    @Test
    void theAmountsAreAllOrOnePerNightAndAnOldCountOfOneLoadsAsOnePerNight() throws Exception {
        seed(true);
        View fresh = onFx(this::load);
        assertEquals(List.of("All", "One per night"), fresh.segment("segmentEnhanceAmount").getItems(), "alphabetical");
        assertEquals("All", fresh.segment("segmentForgeAmount").getValue());

        View oldOne = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_MAX_LEVELS_INT, 1,
                ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT, 1)));
        assertEquals("One per night", oldOne.segment("segmentEnhanceAmount").getValue());
        assertEquals("One per night", oldOne.segment("segmentForgeAmount").getValue());

        View oldFive = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_MAX_LEVELS_INT, 5,
                ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT, 7)));
        assertEquals("All", oldFive.segment("segmentEnhanceAmount").getValue());
        assertEquals("All", oldFive.segment("segmentForgeAmount").getValue());

        View chosen = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_AMOUNT_STRING, "All",
                ConfigurationKeyEnum.HERO_GEAR_FORGE_MAX_BUYS_INT, 1)));
        assertEquals("All", chosen.segment("segmentForgeAmount").getValue(), "a saved choice beats an old count");
        assertEquals(List.of(), oldOne.published, "nothing is written until a choice changes");
    }

    @Test
    void changingAnAmountSavesTheNewKeyOnly() throws Exception {
        View view = onFx(this::load);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_REAL));
        view.published.clear();

        onFx(() -> {
            view.segment("segmentEnhanceAmount").setValue("One per night");
            view.segment("segmentForgeAmount").setValue("One per night");
            return null;
        });

        assertEquals(List.of(Map.entry(ConfigurationKeyEnum.HERO_GEAR_ENHANCE_AMOUNT_STRING, "One per night"),
                Map.entry(ConfigurationKeyEnum.HERO_GEAR_FORGE_AMOUNT_STRING, "One per night")), view.published);
    }

    @Test
    void everyGearActionNamesTheHeroesTheyWorkOnAndFollowsTheTicks() throws Exception {
        View view = onFx(this::load);

        assertEquals("Everything below works only on these heroes: Mia, Flint, Bahiti, Sergey, Molly.", view.label("labelScope").getText());
        assertEquals("Uses Enhancement Parts on the on-role gear of Mia, Flint, Bahiti, Sergey, Molly, the most-used hero first.", view.label("labelEnhanceScope").getText());
        assertEquals("Spends Essence Stones on the on-role gear of Mia, Flint, Bahiti, Sergey, Molly, in the order chosen below.", view.label("labelForgeScope").getText());

        onFx(() -> {
            view.choice("Molly").setSelected(false);
            view.choice("Sergey").setSelected(false);
            return null;
        });

        assertEquals("Everything below works only on these heroes: Mia, Flint, Bahiti.", view.label("labelScope").getText());
        assertEquals("Uses Enhancement Parts on the on-role gear of Mia, Flint, Bahiti, the most-used hero first.", view.label("labelEnhanceScope").getText());
        assertTrue(view.rows("tableUpgrades").stream().noneMatch(r -> r.getFirst().equals("Molly") || r.getFirst().equals("Sergey")),
                "the plan only touches the ticked heroes: " + view.rows("tableUpgrades"));

        onFx(() -> {
            for (String hero : List.of("Mia", "Flint", "Bahiti")) view.choice(hero).setSelected(false);
            return null;
        });
        assertEquals("Nothing is ticked, so the actions below do nothing.", view.label("labelScope").getText());
        assertTrue(view.rows("tableUpgrades").isEmpty());
    }

    @Test
    void theHeroesComeFirstAndTheGearActionsSitUnderThem() throws Exception {
        View view = onFx(this::load);
        VBox below = (VBox) view.namespace().get("boxNightly");

        List<String> titles = view.texts(below).stream().filter(t -> List.of("Heroes to upgrade", "Raise gear levels", "Forge mastery",
                "Wear best spare gear", "Run at").contains(t)).toList();

        assertEquals(List.of("Heroes to upgrade", "Raise gear levels", "Forge mastery", "Wear best spare gear", "Run at"), titles);
        assertTrue(view.texts(below).contains("Puts your best gear on ALL your heroes, not only the ticked ones. Spends nothing."),
                "the one action that is not limited to the ticked heroes says so");
    }

    // ── Results ──────────────────────────────────────────────────────

    @Test
    void onlyTheNightsBuysSayCanAfford() throws Exception {
        seed(true);
        editInventory("\"essenceStones\": 3", "\"essenceStones\": 25");
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_FOCUS_STRING, "Rally squads")));

        onFx(() -> {
            view.segment("segmentForgeAmount").setValue("One per night");
            return null;
        });
        assertEquals(List.of("Can afford", "Tomorrow night", "Short by 5 stones"), view.statuses(3));

        onFx(() -> {
            view.segment("segmentForgeAmount").setValue("All");
            return null;
        });
        assertEquals(List.of("Can afford", "Can afford", "Short by 5 stones"), view.statuses(3));
        assertTrue(view.label("labelStones").getText().startsWith("You have 25 stones, counted "), view.label("labelStones").getText());
    }

    @Test
    void aMissingInventoryIsNotZeroStones() throws Exception {
        seed(true);
        Files.delete(squads().resolve("hero-inventory-1.json"));

        View view = onFx(this::open);

        assertEquals(3, view.rows("tableUpgrades").size());
        assertTrue(view.statuses(3).stream().allMatch(s -> s.equals("Not counted yet")));
        assertEquals("Stones not counted yet", view.label("labelStones").getText());
    }

    @Test
    void theForgePlanShowsThreeRowsAndExpandsToTen() throws Exception {
        View view = onFx(this::load);
        Hyperlink link = (Hyperlink) view.namespace().get("linkUpgrades");

        assertEquals(3, view.rows("tableUpgrades").size());
        assertEquals("Show all 10", link.getText());
        assertEquals("Each row assumes the rows above it were bought.", view.label("labelUpgradesMore").getText());

        onFx(() -> {
            link.fire();
            return null;
        });

        assertEquals(10, view.rows("tableUpgrades").size());
        assertEquals("Show fewer", link.getText());
    }

    @Test
    void oldNumbersCarryAnOutOfDateFlagAndFreshOnesDoNot() throws Exception {
        seed(true);
        String recent = Instant.now().minusSeconds(3600).toString();
        String stale = Instant.now().minusSeconds(40 * 3600).toString();
        editInventory("2026-11-10T23:32:00Z", recent);
        editGear("\"heroWeights\"", "\"scannedAt\": \"" + recent + "\", \"heroWeights\"");
        View fresh = onFx(this::open);
        assertFalse(fresh.label("labelStones").getText().contains("out of date"), fresh.label("labelStones").getText());
        assertFalse(fresh.label("labelGearInfo").getText().contains("out of date"), fresh.label("labelGearInfo").getText());

        editInventory(recent, stale);
        editGear(recent, stale);
        View old = onFx(this::open);
        assertTrue(old.label("labelStones").getText().endsWith("\u00b7 may be out of date"), old.label("labelStones").getText());
        assertTrue(old.label("labelGearInfo").getText().endsWith("\u00b7 out of date"), old.label("labelGearInfo").getText());
        assertTrue(old.label("labelJoiners").getText().contains("Out of date: scan again on the Squads page."), old.label("labelJoiners").getText());
    }

    @Test
    void aFileMissingHeroWeightsShowsAMessageAndKeepsTheOtherTabs() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-gear-1.json"),
                "{\"pieces\":[{\"hero\":\"Mia\",\"slot\":\"Goggles\",\"enhancement\":100,\"masteryPercent\":60,\"onRole\":true}]}");

        View view = onFx(this::open);

        assertEquals("Could not show the plan", view.label("labelUpgradesEmptyTitle").getText());
        assertTrue(view.label("labelUpgradesEmptyText").getText().startsWith("Could not read the saved hero data"),
                view.label("labelUpgradesEmptyText").getText());
        assertTrue(view.rows("tableGear").isEmpty());
        assertEquals(21, view.rows("tableHeroes").size());
    }

    @Test
    void aMalformedFileShowsAMessage() throws Exception {
        seed(true);
        Files.writeString(squads().resolve("hero-inventory-1.json"), "{not json");

        View view = onFx(this::open);

        assertTrue(view.label("labelXp").getText().startsWith("Could not read the saved hero data"), view.label("labelXp").getText());
    }

    @Test
    void aNullProfileClearsThePage() throws Exception {
        View view = onFx(this::load);

        onFx(() -> {
            view.controller().onProfileLoad(null);
            return null;
        });

        assertTrue(view.rows("tableUpgrades").isEmpty());
        assertEquals("Select a profile.", view.label("labelRoster").getText());
        assertEquals("Select a profile.", view.label("labelStatusLine").getText());
        assertFalse(((HBox) view.namespace().get("boxHalt")).isVisible());
    }

    @Test
    void withNoSquadScanTheJoinersAndRosterShowEmptyStates() throws Exception {
        View view = onFx(() -> load(false));

        assertTrue(view.rows("tableJoiners").isEmpty());
        assertFalse(view.table("tableJoiners").isVisible());
        assertTrue(((VBox) view.namespace().get("boxJoinersEmpty")).isVisible());
        assertEquals(List.of("No squad scan yet", "Scan on the Squads page to read your heroes."),
                view.texts((VBox) view.namespace().get("boxRosterEmpty")));
        assertEquals("No squad scan yet.", view.label("labelRoster").getText());
    }

    @Test
    void levelUpsShowHowMuchXpIsShortAsANumberNotRepeatedBadges() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)));

        assertEquals(List.of("Hero", "Level", "XP to next level", "XP short"), view.headers("tableLevels"));
        Map<String, String> short_ = view.rows("tableLevels").stream().collect(Collectors.toMap(r -> r.get(0), r -> r.get(3)));
        assertEquals("1,816,699", short_.get("Flint"));
        assertEquals("2,116,699", short_.get("Mia"));
        assertEquals("", short_.get("Molly"), "a maxed hero has nothing short, so the cell is blank");
        String line = view.label("labelXp").getText();
        assertTrue(line.startsWith("You have 283,301 hero XP, read "), line);
        assertTrue(line.endsWith("Bottles in your Backpack are not counted."), line);
    }

    @Test
    void theDrillCampTableShowsWhateverThePlanLogSwitchSays() throws Exception {
        View view = onFx(this::load);

        assertFalse(view.toggle("switchPlan").isSelected());
        assertTrue(view.table("tableLevels").isVisible(), "the table is data the check read, not a feature behind a switch");
        assertEquals(5, view.rows("tableLevels").size());
    }

    @Test
    void theRosterSaysWhichScanItsLevelsCameFromWithoutALongHeader() throws Exception {
        View view = onFx(this::load);

        assertEquals("Level", view.table("tableHeroes").getColumns().get(3).getText());
        assertEquals("Levels from the Oct 31 scan", view.label("labelRosterAside").getText());
    }

    @Test
    void levelUpsSaysWhenItsLevelsWereRead() throws Exception {
        seed(true);
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)));

        assertTrue(view.label("labelLevelsAside").getText().startsWith("Levels read "), view.label("labelLevelsAside").getText());
    }

    @Test
    void anUnreadRosterCellSaysNotReadInsteadOfADash() throws Exception {
        View view = onFx(this::load);

        List<String> patrick = view.rows("tableHeroes").stream().filter(r -> r.getFirst().equals("Patrick")).findFirst().orElseThrow();
        assertEquals("Not read", patrick.get(2));
        assertTrue(view.rows("tableHeroes").stream().flatMap(List::stream).noneMatch("-"::equals));
    }

    @Test
    void tableHeadersSitLeftOverNamesAndRightOverNumbers() throws Exception {
        View view = onFx(this::load);
        Scene[] scene = new Scene[1];
        onFx(() -> {
            scene[0] = styled(view, 1280, 2600);
            return null;
        });
        onFx(() -> null);

        List<String> wrong = onFx(() -> {
            List<String> found = new ArrayList<>();
            for (String id : List.of("tableGear", "tableUpgrades")) {
                for (Node node : view.table(id).lookupAll(".column-header")) {
                    if (!(node instanceof javafx.scene.control.skin.TableColumnHeader header) || header.getTableColumn() == null) continue;
                    Label label = (Label) header.lookup(".label");
                    boolean number = header.getTableColumn().getUserData().toString().equals("NUMBER");
                    javafx.geometry.Pos want = number ? javafx.geometry.Pos.CENTER_RIGHT : javafx.geometry.Pos.CENTER_LEFT;
                    if (label.getAlignment() != want) found.add(id + " " + header.getTableColumn().getId() + " is " + label.getAlignment());
                }
            }
            scene[0].setRoot(new javafx.scene.layout.Pane());
            return found;
        });
        assertEquals(List.of(), wrong);
    }

    @Test
    void runNowStartsTheNightlyAsAManualRunAndSaysWhyWhenItCannot() throws Exception {
        View view = onFx(this::load);
        Button run = (Button) view.namespace().get("buttonRunNow");

        assertEquals("Run now", run.getText());
        assertTrue(run.isDisable());
        assertEquals("Turn on Run every night first.", view.label("labelRunReason").getText());
        assertTrue(view.label("labelRunReason").isVisible());

        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_OFF, HeroesLayoutController.FORGE_PRACTICE));
        assertFalse(run.isDisable());
        assertFalse(view.label("labelRunReason").isVisible());

        onFx(() -> {
            run.fire();
            return null;
        });
        assertEquals(List.of(1L), view.runs);
        assertTrue(dev.frostguard.engine.squads.GearManualRun.consume(1L), "the routine is told this is a manual run");
        assertEquals(List.of(), view.asked(), "a practice run spends nothing, so it does not ask");
        assertTrue(run.isDisable(), "a second press would do nothing");
        assertEquals("Started. It runs as soon as the bot is free.", view.label("labelRunReason").getText());
    }

    @Test
    void runNowWithSpendingOnAsksFirstAndAnswerNoStartsNothing() throws Exception {
        View view = onFx(this::load);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_PRACTICE));
        view.answer().set(false);

        onFx(() -> {
            ((Button) view.namespace().get("buttonRunNow")).fire();
            return null;
        });

        assertEquals(1, view.asked().size());
        assertTrue(view.asked().getFirst().startsWith("Run now uses your For real settings"), view.asked().getFirst());
        assertEquals(List.of(), view.runs);
        assertFalse(dev.frostguard.engine.squads.GearManualRun.consume(1L));

        view.answer().set(true);
        onFx(() -> {
            ((Button) view.namespace().get("buttonRunNow")).fire();
            return null;
        });
        assertEquals(List.of(1L), view.runs);
        dev.frostguard.engine.squads.GearManualRun.consume(1L);
    }

    @Test
    void runNowSaysToStartTheBotWhenItIsNotRunning() throws Exception {
        View view = onFx(this::load);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_OFF, HeroesLayoutController.FORGE_PRACTICE));
        view.bot().set(false);

        onFx(() -> {
            view.controller().runHook(view.hook());
            return null;
        });

        Button run = (Button) view.namespace().get("buttonRunNow");
        assertTrue(run.isDisable());
        assertEquals("Start the bot to run now.", view.label("labelRunReason").getText());
    }

    @Test
    void anUnreadCostIsOnlyMaxedAtTheLevelCap() throws Exception {
        seed(true);
        editInventory("\"level\": 79, \"xpToNext\": 2400000", "\"level\": 79, \"xpToNext\": null");

        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)));

        Map<String, String> costs = view.rows("tableLevels").stream().collect(Collectors.toMap(r -> r.get(0), r -> r.get(2)));
        assertEquals("Not read", costs.get("Mia"));
        assertEquals("Maxed", costs.get("Molly"));
    }

    @Test
    void reloadsWhenATabIsShownAndWhenThePageIsShown() throws Exception {
        View view = onFx(this::load);

        editInventory("\"essenceStones\": 3", "\"essenceStones\": 25");
        onFx(() -> {
            ((TabPane) view.root().lookup(".tab-pane")).getSelectionModel().select(1);
            return null;
        });
        assertTrue(view.label("labelStones").getText().startsWith("You have 25 stones"), view.label("labelStones").getText());

        editInventory("\"essenceStones\": 25", "\"essenceStones\": 40");
        onFx(() -> new Scene(view.root(), 1100, 1420));
        assertTrue(view.label("labelStones").getText().startsWith("You have 40 stones"), view.label("labelStones").getText());
    }

    // ── Words and times ──────────────────────────────────────────────

    @Test
    void timesReadAsTheDesignBriefWordsThem() {
        ZoneId zone = ZoneId.of("America/New_York");
        Instant now = Instant.parse("2026-11-11T20:00:00Z");

        assertEquals("just now", HeroesLayoutController.friendly(Instant.parse("2026-11-11T19:59:40Z"), now, zone));
        assertEquals("12 minutes ago", HeroesLayoutController.friendly(Instant.parse("2026-11-11T19:48:00Z"), now, zone));
        assertEquals("1 minute ago", HeroesLayoutController.friendly(Instant.parse("2026-11-11T19:58:30Z"), now, zone));
        assertEquals("in 14 min", HeroesLayoutController.friendly(Instant.parse("2026-11-11T20:14:30Z"), now, zone));
        assertEquals("52 minutes ago", HeroesLayoutController.friendly(Instant.parse("2026-11-11T19:08:00Z"), now, zone));
        assertEquals("Today 10:08", HeroesLayoutController.friendly(Instant.parse("2026-11-11T15:08:00Z"), now, zone));
        assertEquals("Yesterday 19:04", HeroesLayoutController.friendly(Instant.parse("2026-11-11T00:04:00Z"), now, zone));
        assertEquals("Sun 18:00", HeroesLayoutController.friendly(Instant.parse("2026-11-08T23:00:00Z"), now, zone));
        assertEquals("Oct 20", HeroesLayoutController.friendly(Instant.parse("2026-10-20T23:00:00Z"), now, zone));
        assertEquals("Nov 3, 2025", HeroesLayoutController.friendly(Instant.parse("2025-11-03T23:00:00Z"), now, zone));
        assertEquals("Today 21:30", HeroesLayoutController.friendly(
                HeroesLayoutController.nextRun("02:30", now, zone).toInstant(), now, zone), "02:30 UTC is 21:30 tonight");
        Instant afterTonight = Instant.parse("2026-11-12T03:00:00Z");
        assertEquals("Tomorrow 21:30", HeroesLayoutController.friendly(
                HeroesLayoutController.nextRun("02:30", afterTonight, zone).toInstant(), afterTonight, zone));
        assertEquals("nonsense UTC", HeroesLayoutController.localTime("nonsense"));
    }

    @Test
    void aSavedReasonDropsTheStepNameThatAlreadyLeadsIt() {
        assertEquals("Mia Boots showed no new level", HeroesLayoutController.plainReason("Enhance: Mia Boots showed no new level"));
        assertEquals("Mia slot 1 went down", HeroesLayoutController.plainReason("Quick Equip: Mia slot 1 went down"));
        assertEquals("something else", HeroesLayoutController.plainReason("something else"));
        assertEquals("", HeroesLayoutController.plainReason(null));
        assertEquals("hero-pill-ok", HeroesLayoutController.pillStyle("A"));
        assertEquals("hero-pill-warn", HeroesLayoutController.pillStyle("Short by 7"));
        assertEquals("hero-pill-error", HeroesLayoutController.pillStyle("D"));
        assertEquals("hero-pill-off", HeroesLayoutController.pillStyle("Tomorrow night"));
    }

    // ── The design brief's review checks, run on the real FXML ───────

    @Test
    void captionsAndDisabledTextKeepTheBriefsContrastAndSize() throws Exception {
        seed(true);
        new HeroGearStore(workspace).saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level",
                Instant.now().minusSeconds(3600).toString(), HeroGearStore.GearHalt.ENHANCE));
        View view = onFx(() -> open(Map.of(ConfigurationKeyEnum.HERO_GEAR_FORGE_ENABLED_BOOL, true)));
        Scene[] scene = new Scene[1];
        onFx(() -> {
            scene[0] = styled(view, 780, 2600);
            return null;
        });

        List<String> weak = onFx(() -> {
            List<String> found = new ArrayList<>();
            javafx.scene.paint.Color card = javafx.scene.paint.Color.web("#181c24");
            for (Node node : view.root().lookupAll(".hero-caption")) {
                if (!(node instanceof Label label) || !shown(node) || label.getText().isBlank()) continue;
                if (label.getFont().getSize() < 12) found.add("caption " + label.getText() + " is " + label.getFont().getSize() + "px");
                if (!label.getTextFill().equals(javafx.scene.paint.Color.web("#9eaab6"))) found.add("caption " + label.getText() + " is " + label.getTextFill());
                if (contrast((javafx.scene.paint.Color) label.getTextFill(), card) < 4.5) found.add("caption " + label.getText() + " is faint");
            }
            for (Node node : view.root().lookupAll(".combo-box-base")) {
                if (!(node instanceof ComboBox<?> combo) || !combo.isDisabled() || !shown(combo)) continue;
                Node cell = combo.lookup(".list-cell");
                if (cell instanceof Labeled text && text.getText() != null && !text.getText().isBlank()) {
                    double ratio = contrast((javafx.scene.paint.Color) text.getTextFill(), javafx.scene.paint.Color.web("#14171f"));
                    if (ratio < 4.5) found.add("a greyed control reads " + text.getText() + " at " + ratio + ":1");
                    if (combo.getOpacity() < 1 || text.getFont().getSize() < 12) found.add("a greyed control is faded or small: " + text.getText());
                }
            }
            scene[0].setRoot(new javafx.scene.layout.Pane());
            return found;
        });

        assertEquals(List.of(), weak);
    }

    /** The WCAG contrast ratio of two colours. */
    private static double contrast(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(javafx.scene.paint.Color c) {
        double[] channels = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) {
            channels[i] = channels[i] <= 0.03928 ? channels[i] / 12.92 : Math.pow((channels[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }

    @Test
    void thePageFollowsTheBriefAtTheTwoWidthsTheAppUses() throws Exception {
        View view = onFx(this::load);
        onFx(() -> view.tick(true, HeroesLayoutController.ENHANCE_PRACTICE, HeroesLayoutController.FORGE_PRACTICE));
        new HeroGearStore(workspace).saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level",
                Instant.now().minusSeconds(3600).toString(), HeroGearStore.GearHalt.ENHANCE));
        View halted = onFx(this::open);
        onFx(() -> halted.tick(true, HeroesLayoutController.ENHANCE_REAL, HeroesLayoutController.FORGE_REAL));

        List<String> failures = new ArrayList<>();
        for (int width : List.of(900, 1280)) {
            failures.addAll(check(view, width));
            failures.addAll(check(halted, width));
        }

        assertEquals(List.of(), failures);
    }

    @Test
    void contentNeverStretchesPastNineHundredSixtyPixels() throws Exception {
        View view = onFx(this::load);

        double width = onFx(() -> {
            Scene scene = styled(view, 1920, 1000);
            Node page = view.root().lookup(".hero-page");
            return page.getBoundsInLocal().getWidth() > 0 ? ((Parent) page).getLayoutBounds().getWidth() : -1;
        });

        assertTrue(width > 0 && width <= 960, "page width " + width);
    }

    private List<String> check(View view, int width) throws Exception {
        return onFx(() -> {
            Scene scene = styled(view, width, 2800);
            List<String> found = new ArrayList<>();
            TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
            for (int tab = 0; tab < tabs.getTabs().size(); tab++) {
                tabs.getSelectionModel().select(tab);
                view.root().applyCss();
                view.root().layout();
                scene.snapshot(null);
                found.addAll(problems(view, width));
            }
            tabs.getSelectionModel().select(0);
            scene.setRoot(new javafx.scene.layout.Pane());
            return found;
        });
    }

    private List<String> problems(View view, int width) {
        List<String> found = new ArrayList<>();
        {
            view.root().lookupAll("*").stream().filter(n -> shown(n)).forEach(node -> {
                if (node.getStyleClass().stream().anyMatch(c -> c.startsWith("header-"))) found.add(width + "px: solid header bar " + node.getStyleClass());
                if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                    String text = labeled.getText();
                    if (labeled.getStyleClass().contains("hero-column-title") && labeled.getTextFill().toString().equals("0x000000ff")) {
                        found.add(width + "px: column title " + text + " is black on dark");
                    }
                    if (labeled.getFont().getSize() < 12) found.add(width + "px: " + text + " is " + labeled.getFont().getSize() + "px");
                    if (text.endsWith("...") || text.endsWith("\u2026")) found.add(width + "px: ellipsis " + text);
                    if (text.contains("null")) found.add(width + "px: null in " + text);
                    if (!labeled.isWrapText() && !labeled.getStyleClass().contains("tab-label")
                            && !labeled.getStyleClass().contains("hero-column-title") && labeled.getWidth() + 1 < labeled.prefWidth(-1)
                            && !(labeled.getParent() != null && labeled.getParent().getStyleClass().contains("column-header"))) {
                        found.add(width + "px: cut short " + text + " (" + labeled.getWidth() + " of " + labeled.prefWidth(-1) + ")");
                    }
                }
            });
            view.root().lookupAll(".hero-card").forEach(card -> {
                long primaries = card.lookupAll(".hero-button-primary").stream().filter(n -> n.isVisible()).count();
                if (primaries > 1) found.add(width + "px: " + primaries + " primary buttons in one card");
            });
            List<Double> lefts = view.root().lookupAll(".hero-control").stream().filter(n -> shown(n))
                    .map(n -> n.localToScene(0, 0).getX()).distinct().toList();
            if (lefts.size() > 1) found.add(width + "px: controls start at different places " + lefts);
            view.root().lookupAll(".hero-control").stream().filter(n -> shown(n)).forEach(control -> {
                double edge = control.localToScene(control.getBoundsInLocal()).getMaxX();
                double right = ((Parent) control).getChildrenUnmodifiable().stream().filter(Node::isVisible)
                        .mapToDouble(n -> n.localToScene(n.getBoundsInLocal()).getMaxX()).max().orElse(edge);
                if (Math.abs(edge - right) > 1) found.add(width + "px: a control stops " + (edge - right) + "px short of the shared right edge");
            });
            Map<String, Integer> room = Map.of("segmentEnhance", 230, "segmentForge", 230, "segmentEnhanceAmount", 230, "segmentForgeAmount", 230, "comboBoxFocus", 200);
            room.forEach((id, least) -> {
                javafx.scene.layout.Region control = (javafx.scene.layout.Region) view.namespace().get(id);
                if (shown(control) && control.getWidth() < least) found.add(width + "px: " + id + " is only " + control.getWidth() + " wide");
            });
        }
        return found;
    }

    private static Scene styled(View view, int width, int height) {
        Scene scene = new Scene(view.root(), width, height);
        scene.getStylesheets().add(Objects.requireNonNull(HeroesLayoutController.class.getResource("/styles/style.css")).toExternalForm());
        view.root().applyCss();
        view.root().layout();
        scene.snapshot(null);
        return scene;
    }

    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    // ── Rendering for review ─────────────────────────────────────────

    @Test
    void rendersTheStatesForReview() throws Exception {
        renderState("1-real-data.png", false, true, false, false);
        renderState("2-empty.png", false, false, true, false);
        renderState("3-halted.png", true, true, false, false);
        renderState("4-for-real.png", false, true, false, true);
    }

    private void renderState(String name, boolean halt, boolean nightly, boolean empty, boolean real) throws Exception {
        seedPinnedDay();
        if (empty) {
            Files.deleteIfExists(squads().resolve("hero-gear-1.json"));
            Files.deleteIfExists(squads().resolve("hero-inventory-1.json"));
            Files.deleteIfExists(squads().resolve("hero-gear-night-1.json"));
            Files.deleteIfExists(squads().resolve("profile-1.json"));
        }
        HeroGearStore store = new HeroGearStore(workspace);
        if (halt) {
            // A halt can only come from a night that really presses: Raise levels was For real, and the night stopped at 21:33.
            Files.writeString(squads().resolve("hero-gear-night-1.json"), "{\"startedAt\": \"2026-11-12T02:31:00Z\", \"slot\": \"2026-11-12T02:30:00Z\","
                    + " \"buys\": 0, \"levels\": 1, \"finished\": false, \"mode\": \"live\"}");
            store.saveHalt(1, new HeroGearStore.GearHalt("Enhance: Mia Boots showed no new level", "2026-11-12T02:33:00Z",
                    HeroGearStore.GearHalt.ENHANCE));
        } else {
            store.clearHalt(1);
        }
        View view = onFx(() -> openPinned(Map.of(ConfigurationKeyEnum.HERO_UPGRADE_PLAN_ENABLED_BOOL, true)));
        onFx(() -> view.tick(nightly, real || halt ? HeroesLayoutController.ENHANCE_REAL : HeroesLayoutController.ENHANCE_PRACTICE,
                real ? HeroesLayoutController.FORGE_REAL : HeroesLayoutController.FORGE_PRACTICE));
        String renderDir = System.getProperty("heroes.render.dir");
        onFx(() -> {
            Scene scene = styled(view, Integer.getInteger("heroes.render.width", 1280), 2600);
            javafx.scene.image.WritableImage image = scene.snapshot(null);
            if (renderDir != null) {
                Path dir = Files.createDirectories(Path.of(renderDir));
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", dir.resolve(name).toFile());
                TabPane tabs = (TabPane) view.root().lookup(".tab-pane");
                for (int i = 1; i < tabs.getTabs().size(); i++) {
                    tabs.getSelectionModel().select(i);
                    view.root().applyCss();
                    view.root().layout();
                    ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png",
                            dir.resolve(name.replace(".png", "-tab" + (i + 1) + ".png")).toFile());
                }
            }
            return null;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private record View(Parent root, Map<String, Object> namespace, HeroesLayoutController controller,
                        List<Map.Entry<ConfigurationKeyEnum, Object>> published, List<String> asked, AtomicBoolean answer,
                        List<Long> runs, AtomicBoolean bot, HeroesLayoutController.RunHook hook) {
        @SuppressWarnings("unchecked")
        TableView<List<String>> table(String id) {
            return (TableView<List<String>>) namespace.get(id);
        }

        List<List<String>> rows(String id) {
            return table(id).getItems();
        }

        List<String> headers(String id) {
            return table(id).getColumns().stream().map(TableColumn::getId).toList();
        }

        Label label(String id) {
            return (Label) namespace.get(id);
        }

        Segmented segment(String id) {
            return (Segmented) namespace.get(id);
        }

        ToggleSwitch toggle(String id) {
            return (ToggleSwitch) namespace.get(id);
        }

        @SuppressWarnings("unchecked")
        <T> ComboBox<T> combo(String id) {
            return (ComboBox<T>) namespace.get(id);
        }

        List<CheckBox> choices() {
            return ((GridPane) namespace.get("gridHeroes")).getChildren().stream().map(CheckBox.class::cast).toList();
        }

        CheckBox choice(String hero) {
            return choices().stream().filter(c -> c.getText().equals(hero)).findFirst().orElseThrow();
        }

        List<String> statuses(int count) {
            return IntStream.range(0, count).mapToObj(r -> rows("tableUpgrades").get(r).get(6)).toList();
        }

        List<String> texts(Parent parent) {
            List<String> found = new ArrayList<>();
            collect(parent, found);
            return found;
        }

        private static void collect(Node node, List<String> found) {
            if (node instanceof Label label) found.add(label.getText());
            if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(c -> collect(c, found));
        }

        List<String> tabTitles() {
            return ((TabPane) root.lookup(".tab-pane")).getTabs().stream().map(Tab::getText).toList();
        }

        boolean disabled(String id) {
            return ((Node) namespace.get(id)).isDisabled();
        }

        Void tick(boolean nightly, String enhance, String forge) {
            toggle("switchNightly").setSelected(nightly);
            segment("segmentEnhance").setValue(enhance);
            segment("segmentForge").setValue(forge);
            return null;
        }
    }

    private View load() throws Exception {
        return load(true);
    }

    private View load(boolean withScan) throws Exception {
        seed(withScan);
        return open();
    }

    private Path squads() {
        return workspace.resolve("squads");
    }

    private void editInventory(String from, String to) throws Exception {
        edit("hero-inventory-1.json", from, to);
    }

    private void editGear(String from, String to) throws Exception {
        edit("hero-gear-1.json", from, to);
    }

    private void edit(String name, String from, String to) throws Exception {
        Path file = squads().resolve(name);
        String json = Files.readString(file);
        assertTrue(json.contains(from), from);
        Files.writeString(file, json.replace(from, to));
    }

    private void seed(boolean withScan) throws Exception {
        Path dir = Files.createDirectories(squads());
        for (String name : List.of("hero-gear", "hero-inventory")) {
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/" + name + ".json"))) {
                Files.copy(in, dir.resolve(name + "-1.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (withScan) {
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/live-snapshot.json"))) {
                Files.copy(in, dir.resolve("profile-1.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Only the first seed remembers the real setting, so a test that seeds twice still restores it.
        if (!workspaceSaved) {
            previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            workspaceSaved = true;
        }
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());
    }

    private View open() throws Exception {
        return open(Map.of());
    }

    private View open(Map<ConfigurationKeyEnum, Object> settings) throws Exception {
        return open(settings, (Instant) null);
    }

    private View openPinned(Map<ConfigurationKeyEnum, Object> settings) throws Exception {
        return open(settings, THURSDAY_TEN);
    }

    private View open(Map<ConfigurationKeyEnum, Object> settings, Instant pinnedAt) throws Exception {
        FXMLLoader loader = new FXMLLoader(HeroesLayoutController.class.getResource("/layout/HeroesLayout.fxml"));
        HeroesLayoutController controller = new HeroesLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        ProfileAux profile = new ProfileAux(1L, "Default", "0", true, 50L, "", 0L);
        settings.forEach(profile::setConfig);
        if (pinnedAt != null) controller.clock(pinnedAt, NEW_YORK);
        // The real hook would reach for the bot's schedule service, so the page never loads without a stand-in.
        controller.runHook(new HeroesLayoutController.RunHook() {
            @Override
            public boolean running(long profileId) {
                return true;
            }

            @Override
            public void run(long profileId) {
            }
        });
        controller.onProfileLoad(profile);
        List<Map.Entry<ConfigurationKeyEnum, Object>> published = new ArrayList<>();
        controller.attachProfileListener((key, value) -> published.add(Map.entry(key, value)));
        List<String> asked = new ArrayList<>();
        AtomicBoolean answer = new AtomicBoolean(true);
        controller.spendConfirmer(question -> {
            asked.add(question);
            return answer.get();
        });
        List<Long> runs = new ArrayList<>();
        AtomicBoolean bot = new AtomicBoolean(true);
        HeroesLayoutController.RunHook hook = new HeroesLayoutController.RunHook() {
            @Override
            public boolean running(long profileId) {
                return bot.get();
            }

            @Override
            public void run(long profileId) {
                runs.add(profileId);
            }
        };
        controller.runHook(hook);
        return new View(root, loader.getNamespace(), controller, published, asked, answer, runs, bot, hook);
    }

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
