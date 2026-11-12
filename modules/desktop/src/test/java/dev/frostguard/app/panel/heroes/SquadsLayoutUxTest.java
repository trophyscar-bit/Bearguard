package dev.frostguard.app.panel.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import atlantafx.base.theme.PrimerDark;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.JavaFxToolkit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The Squads page, loaded the way the app loads it. The fixture is the snapshot of a real live
 * scan (21 heroes, 12 squad slots). Three states matter to a player: a good scan, no scan yet, and
 * a scan that came back incomplete.
 *
 * <p>Optional visual check: -Dsquads.render.dir=&lt;folder&gt; writes every state at 1280 and 900
 * wide, each tab, with the app's own theme.
 */
class SquadsLayoutUxTest {

    private enum Scan { REAL, MATCH, NONE, INCOMPLETE }

    @TempDir
    Path workspace;

    private String previousWorkspace;
    private boolean workspaceRemembered;

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
    }

    @AfterEach
    void restoreWorkspace() {
        // A test that never loaded the page never touched the property, so it has nothing to put back.
        if (!workspaceRemembered) return;
        if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
    }

    @Test
    void everyTabFillsFromTheSavedScan() throws Exception {
        View view = onFx(() -> load(Scan.REAL));

        // Each fact once. The header names the bear; the card says if it is ready; the banner says what the scan missed.
        assertFalse(view.label("labelScan").isVisible());
        // The bear is named once, in the card title; the header does not repeat it.
        assertFalse(view.status().getText().contains("Bear Trap"), view.status().getText());
        // One sentence; the scan did not stop partway, so there is no cause or how-to to add.
        assertEquals("The scan couldn't read all of Jessie's stats and found squad 3 empty or locked.", view.label("bannerTitle").getText());
        assertEquals("To read Jessie's stats: open Jessie in the game, then press Scan now.", view.label("bannerText").getText());
        assertTrue(view.status().getText().contains("Last scan "), view.status().getText());
        // One scan control: the header's. The banner has no button of its own.
        assertFalse(((javafx.scene.Node) view.namespace().get("bannerAction")).isVisible());

        String strip = allText(view.bearStrip());
        // Squad 1's counts (1 infantry, 1 lancer) look misread and feed the total, so the figure is approximate.
        assertTrue(strip.contains("Not ready: short about 54,860 marksmen"), strip);
        assertTrue(strip.contains("Short ~54,860") && !strip.contains("at least"), strip);
        // The shortfall is the headline and the pill, and not a third time in a list.
        assertFalse(strip.contains("Short of marksmen by"), strip);
        // Jessie's unread stats are in the banner only.
        assertFalse(strip.contains("Couldn't read Jessie's stats"), strip);
        assertFalse(strip.contains("Checked "), strip);
        assertTrue(strip.contains("Fix first: 3 heroes are in two Bear Trap squads: Jasser (2, 8), Molly (2, 8), Sergey (1, 2)."), strip);
        // The other heroes in two squads are the per-row badges, not a second list.
        assertFalse(strip.contains("Also in more than one squad"), strip);
        assertEquals(1, strip.split("Bear Trap (Today|Tomorrow|[A-Z][a-z]{2}) 14:00", -1).length - 1, strip);
        assertEquals(1, strip.split("Fix first", -1).length - 1, "every clash is one line, said once: " + strip);
        // One value per cell: only the compared first hero.
        assertFalse(strip.contains("Jessie, Bahiti, Sergey") || strip.contains("Jasser, Molly, Flint"), strip);
        for (String role : new String[] {"Lead (squad 8)", "Join 1 (squad 1)", "Join 2 (squad 2)", "Join 3 (squad 3)", "Troops 1 (squad 4)", "Troops 2 (squad 5)"}) {
            assertTrue(strip.contains(role), role + ": " + strip);
        }
        // One pill per Result cell, by priority: a duplicate names its hero and outranks the comparison.
        assertTrue(strip.contains("Shared hero"), strip);
        assertTrue(strip.contains("2 shared") && strip.contains("1 shared") && strip.contains("3 shared"), strip);
        assertFalse(strip.contains("Bahiti") || strip.contains("squads " + "\u00b7"), "troops-only squads are not a clash: " + strip);
        // Result is the comparison only, so it is Differs / Stats not read / Empty or locked, and never a hero.
        assertTrue(strip.contains("Differs") && strip.contains("Stats not read") && strip.contains("Has heroes"), strip);
        assertFalse(strip.contains("Duplicate pills"), "no legend caption: " + strip);
        assertFalse(strip.contains("Duplicate hero") || strip.contains("Your pick"), strip);
        // Squad 3 is the same word everywhere: the Saved table, the banner and this pill.
        assertTrue(strip.contains("Empty or locked"), strip);
        assertEquals("Empty or locked", view.table("tableSaved").getItems().get(2).get(1));
        assertFalse(strip.contains("Duplicate pills"), strip);
        // Squad 3 was not read, but what to suggest does not depend on it: the table and the tab agree.
        assertTrue(strip.contains("Alonso"), strip);
        assertTrue(allText(view.suggested()).contains("Alonso"), allText(view.suggested()));
        assertTrue(strip.contains(BearTrapCheck.NONE), strip);
        assertTrue(strip.indexOf("Marksmen") < strip.indexOf("Squad Your leader"), "marksmen come first: " + strip);

        String suggested = allText(view.suggested());
        assertFalse(suggested.contains("Jessie"), suggested);
        assertFalse(suggested.contains("Scan incomplete"), suggested);
        // One rule for a scan that missed something: suggestions use only the heroes it read.
        assertTrue(suggested.contains("Joiners only use the leader's first expedition skill. Suggestions use only the heroes the scan read."), suggested);
        assertTrue(suggested.contains("Joiners only use the leader's first expedition skill."), suggested);
        assertTrue(suggested.contains("Level") && suggested.contains("Bonus") && suggested.contains("Why"), suggested);
        // Identical rows are one row, named for all their uses.
        assertTrue(suggested.contains("Bear Trap - lead, Attack rally lead and Field attack (solo march)"), suggested);
        assertEquals(1, suggested.split("Attack rally lead", -1).length - 1, suggested);
        assertTrue(strip.contains("Your leader") && strip.contains("Suggested leader"), strip);

        assertEquals(12, view.table("tableSaved").getItems().size());
        assertEquals(3, view.table("tableTroops").getItems().size());
        TabPane tabs = (TabPane) view.namespace().get("tabsResults");
        assertEquals(List.of("Saved squads", "Suggested heroes", "Troops at home"), tabs.getTabs().stream().map(t -> t.getText()).toList());
        assertEquals(7, view.table("tableSaved").getColumns().size());
        assertEquals("Check counts: 1 infantry, 1 lancer", view.table("tableSaved").getItems().get(0).get(1));
        assertEquals("No troops", view.table("tableSaved").getItems().get(4).get(1));
        assertEquals(BearTrapCheck.NONE, view.table("tableSaved").getItems().get(4).get(6)); // no troops: nothing to read
        assertEquals("", view.table("tableSaved").getItems().get(8).get(3));
        String tiles = allText((javafx.scene.Node) view.namespace().get("tilesTroops"));
        assertTrue(tiles.contains("553,807"), tiles);
        assertTrue(tiles.contains("The game rounds this to 553.8K."), tiles);
    }

    @Test
    void theCardsAreGroupedByWhatTheOwnerIsDoing() throws Exception {
        View view = onFx(() -> load(Scan.REAL));
        javafx.scene.Node root = ((javafx.scene.control.ScrollPane) view.root()).getContent();
        String page = allText(root);
        // Three groups in the order the owner thinks in, each with a one-line explainer.
        int jobs = page.indexOf("What each squad is for");
        int join = page.indexOf("Troops per join and the nightly re-check");
        int heroes = page.indexOf("Heroes per squad");
        assertTrue(jobs >= 0 && join > jobs && heroes > join, page);
        assertTrue(page.contains("Every squad the bot uses has one job in the Bear Trap."), page);
        assertTrue(page.contains("Each Bear Trap join sends this many troops."), page);
        assertTrue(page.contains("Who should lead and who should join the bear"), page);
        // The old catch-all Settings card is gone.
        assertFalse(page.contains("Settings"), page);
        assertFalse(page.contains("Run at"), page);
    }

    @Test
    void eachSquadIsShownByItsJobAndTheScopeIsSaid() throws Exception {
        View view = onFx(() -> load(Scan.REAL));
        String jobs = allText((javafx.scene.Node) view.namespace().get("listJobs"));
        assertTrue(jobs.contains("Rally host"), jobs);
        assertTrue(jobs.contains("Bear join with heroes"), jobs);
        assertTrue(jobs.contains("Bear join, troops only"), jobs);
        assertTrue(jobs.indexOf("Squad 1") < jobs.indexOf("Squad 2") && jobs.indexOf("Squad 5") < jobs.indexOf("Squad 8"),
                "squads stay in number order: " + jobs);
        // A saved squad that has no job is named, so nothing is silently ignored.
        String note = view.label("labelJobsNote").getText();
        assertTrue(note.startsWith("Saved but not used by the bot:"), note);
        assertEquals("Applies to join squads 1, 2, 3, 4 and 5 only. Squad 8, your rally, always sends as many as it can carry.",
                view.label("labelScope").getText());
    }

    @Test
    void aSquadThatDoesNotSendTheSettingIsFlaggedInWords() throws Exception {
        View view = onFx(() -> load(Scan.REAL));
        String jobs = allText((javafx.scene.Node) view.namespace().get("listJobs"));
        // The real Oct 31 scan: squad 1 sends 69,698, not the 100,000 setting.
        assertTrue(jobs.contains("Sends 69,698, but Troops per join is 100,000. Edit the squad in the game to match."), jobs);
    }

    @Test
    void theLiveStateReadsInWordsAndTheRunTimeIsAnHourList() throws Exception {
        View view = onFx(() -> load(Scan.REAL));
        String plan = view.label("labelPlan").getText();
        assertTrue(plan.contains("Your rally squad takes") && plan.contains("You can send:"), plan);
        javafx.scene.control.ComboBox<?> runAt = (javafx.scene.control.ComboBox<?>) view.namespace().get("comboRunAt");
        // Whole hours (24), plus the stored time when it is not on the hour.
        assertTrue(runAt.getItems().size() <= 25, "not 96 quarter-hours: " + runAt.getItems().size());
        assertTrue(runAt.getItems().contains(runAt.getValue()));
    }

    @Test
    void noScanYetSaysTheTroopsAreNotKnown() throws Exception {
        View view = onFx(() -> load(Scan.NONE));
        assertEquals("Not known yet. Press Scan now to read your troops.", view.label("labelPlan").getText());
        assertTrue(allText((javafx.scene.Node) view.namespace().get("listJobs")).contains("Press Scan now to read your squads."));
    }

    @Test
    void theSuggestedBearSquadsNeverUseOneHeroTwice() throws Exception {
        onFx(() -> load(Scan.REAL));
        dev.frostguard.api.squads.SquadSnapshot snap = new dev.frostguard.engine.squads.SquadSnapshotStore(workspace).load(1L).orElseThrow();
        // The duplicates on the page are on the "yours" side. The suggested lead and joiners share no hero.
        java.util.Set<String> seen = new java.util.HashSet<>();
        var plan = dev.frostguard.engine.squads.SquadPlanner.plan(snap);
        for (int i = 0; i < 4; i++) {
            for (String hero : plan.squads().get(i).heroes()) {
                assertTrue(seen.add(hero), hero + " is suggested twice for the Bear Trap");
            }
        }
    }

    @Test
    void aMatchingScanWithEnoughMarksmenIsAllSet() throws Exception {
        View view = onFx(() -> load(Scan.MATCH));

        assertTrue(view.label("labelScan").getText().startsWith("Scan complete"), view.label("labelScan").getText());
        assertFalse(view.status().getText().contains("Bear Trap"), view.status().getText());
        assertFalse(view.bannerBox().isVisible(), "a healthy scan needs no banner");
        String strip = allText(view.bearStrip());
        assertEquals(1, strip.split("All set", -1).length - 1, "All set once: " + strip);
        assertFalse(view.status().getText().contains("All set"), view.status().getText());
        // The scan's time is in the header's "Scan complete" line, so the card does not repeat it.
        assertFalse(strip.contains("Checked"), strip);
        assertTrue(strip.contains("Match"), strip);
        assertFalse(strip.contains("Differs"), strip);
        assertTrue(strip.contains("Enough"), strip);
        assertFalse(strip.contains("Fix first"), strip);
        assertFalse(strip.contains("Shared hero"), "no one shares a hero, so the column is not shown: " + strip);
    }

    @Test
    void noScanYetShowsNoBannerAndAnExplainedEmptyState() throws Exception {
        View view = onFx(() -> load(Scan.NONE));

        assertTrue(view.status().getText().contains("Not scanned yet"), view.status().getText());
        assertFalse(view.label("labelScan").isVisible());
        assertFalse(view.bannerBox().isVisible(), "a healthy, empty page needs no status banner");
        assertFalse(view.bearStrip().isVisible());
        assertEquals(1, view.suggested().getChildren().size());
        assertTrue(view.table("tableSaved").getItems().isEmpty());
        assertTrue(view.table("tableSaved").getPlaceholder() != null);
        // Nothing scanned and no banner: the empty tab says what to do. (With a banner, the banner owns it.)
        assertTrue(allText(view.suggested()).contains("Press Scan now to read your squads."), allText(view.suggested()));
    }

    @Test
    void aScanThatNeverReachedTroopsSaysWhatItCouldNotRead() throws Exception {
        View view = onFx(() -> load(Scan.INCOMPLETE));

        assertEquals("The scan couldn't read your marksmen at home, 6 squads and 18 heroes.", view.label("bannerTitle").getText());
        // Each cause once, and they agree: the troops and squads did not open, the heroes changed.
        String banner = view.label("bannerText").getText();
        assertEquals("Chief Profile didn't open during the scan. The Heroes screen changed during the scan. Open Chief Profile and Heroes in the game once, then press Scan now.", banner);
        assertFalse(view.label("labelScan").isVisible());
        assertFalse(view.status().getText().contains("Bear Trap"), view.status().getText());
        String strip = allText(view.bearStrip());
        // The banner carries what was not read; the card is just "Not ready".
        assertTrue(strip.contains("Not ready") && !strip.contains("Not ready:"), strip);
        // Nothing was read, so each section is one "Not read" line: no headings, no row of dashes.
        assertTrue(strip.contains("Marksmen and squads Not read"), strip);
        assertFalse(strip.contains("Needed") || strip.contains("Suggested") || strip.contains(BearTrapCheck.NONE), strip);
        assertEquals(1, strip.split("Not read(?!y)", -1).length - 1, strip);
        assertFalse(strip.contains("Scan incomplete") || strip.contains("No hero left"), strip);
        // The same rule, with nothing to apply it to.
        assertTrue(allText(view.suggested()).contains("Nothing to suggest yet"), allText(view.suggested()));
        assertTrue(allText(view.suggested()).contains("Suggestions use only the heroes the scan read. No heroes read yet."), allText(view.suggested()));
        assertFalse(allText(view.suggested()).contains("Scan now"));
        assertTrue(view.table("tableTroops").getItems().isEmpty());
    }

    @Test
    void theNextBearTimeIsInThePlayersClock() {
        ZoneId ny = ZoneId.of("America/New_York");
        assertEquals("Tomorrow 14:00", SquadsLayoutController.bearTimeLabel("12-11-2026 19:00",
                Instant.parse("2026-11-11T22:00:00Z"), ny));
        assertEquals("Thu 14:00", SquadsLayoutController.bearTimeLabel("12-11-2026 19:00",
                Instant.parse("2026-11-10T22:00:00Z"), ny));
        // The setting repeats every 48 hours.
        assertEquals("Sat 14:00", SquadsLayoutController.bearTimeLabel("12-11-2026 19:00",
                Instant.parse("2026-11-12T21:00:00Z"), ny));
        assertEquals(null, SquadsLayoutController.bearTimeLabel("", Instant.now(), ny));
    }

    @Test
    void theLocalTimeListRoundTripsToUtc() {
        String local = SquadsLayoutController.toLocal("07:15");
        assertEquals("07:15", SquadsLayoutController.toUtc(local));
    }

    private static String allText(javafx.scene.Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(javafx.scene.Node node, StringBuilder sb) {
        if (node instanceof Label l && l.getText() != null) sb.append(l.getText()).append(' ');
        if (node instanceof javafx.scene.Parent p) for (javafx.scene.Node c : p.getChildrenUnmodifiable()) collect(c, sb);
    }

    @Test
    void relativeTimeFollowsTheBrief() {
        ZoneId utc = ZoneId.of("UTC");
        Instant now = Instant.parse("2026-11-11T15:00:00Z");
        assertEquals("just now", SquadsLayoutController.relativeTime(now.minusSeconds(20), now, utc));
        assertEquals("12 minutes ago", SquadsLayoutController.relativeTime(now.minusSeconds(12 * 60), now, utc));
        assertEquals("Today 07:15", SquadsLayoutController.relativeTime(Instant.parse("2026-11-11T07:15:00Z"), now, utc));
        assertEquals("Yesterday 07:15", SquadsLayoutController.relativeTime(Instant.parse("2026-11-10T07:15:00Z"), now, utc));
        assertEquals("Sat 07:15", SquadsLayoutController.relativeTime(Instant.parse("2026-11-07T07:15:00Z"), now, utc));
        assertEquals("Oct 31", SquadsLayoutController.relativeTime(Instant.parse("2026-10-31T17:58:00Z"), now, utc));
        assertEquals("Nov 3, 2025", SquadsLayoutController.relativeTime(Instant.parse("2025-11-03T07:15:00Z"), now, utc));
    }

    @Test
    void troopTotalsAreSpelledOutNotCompacted() {
        assertEquals("553,800 / 553,800", SquadsLayoutController.expandPair("553.8K/553.8K"));
        assertEquals("0 / 153,300", SquadsLayoutController.expandPair("0/153.3K"));
        assertEquals("1,200,000", SquadsLayoutController.expandCount("1.2M"));
        assertEquals("not a number", SquadsLayoutController.expandCount("not a number"));
    }

    @Test
    void renderEveryStateWhenAsked() throws Exception {
        String renderDir = System.getProperty("squads.render.dir");
        if (renderDir == null) return;
        for (Scan scan : Scan.values()) {
            for (int width : new int[] {1280, 900}) {
                String[] names = {"saved", "suggested", "troops"};
                for (int tab = 0; tab < names.length; tab++) {
                    int index = tab;
                    View view = onFx(() -> load(scan));
                    onFx(() -> render(view, Path.of(renderDir), scan.name().toLowerCase(), width, index, names[index]));
                }
            }
        }
    }

    private record View(Parent root, Map<String, Object> namespace) {
        @SuppressWarnings("unchecked")
        TableView<List<String>> table(String id) {
            return (TableView<List<String>>) namespace.get(id);
        }

        Label status() {
            return label("labelStatus");
        }

        Label label(String id) {
            return (Label) namespace.get(id);
        }

        Label bannerText() {
            return label("bannerText");
        }

        HBox bannerBox() {
            return (HBox) namespace.get("bannerBox");
        }

        VBox suggested() {
            return (VBox) namespace.get("listSuggested");
        }

        VBox bearStrip() {
            return (VBox) namespace.get("bearStrip");
        }
    }

    private View load(Scan scan) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("squads"));
        Path target = dir.resolve("profile-1.json");
        Files.deleteIfExists(target);
        if (scan != Scan.NONE) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode snapshot;
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/squads/live-snapshot.json"))) {
                snapshot = (ObjectNode) mapper.readTree(in);
            }
            // A scan the player is looking at has a recent time stamp.
            snapshot.put("capturedAt", Instant.now().minusSeconds(2 * 3600).toString());
            if (scan == Scan.MATCH) {
                // Squads that are exactly what the scan suggests, and marksmen to spare.
                dev.frostguard.api.squads.SquadSnapshot read = mapper.disable(
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .treeToValue(snapshot, dev.frostguard.api.squads.SquadSnapshot.class);
                dev.frostguard.engine.squads.SquadPlanner.Plan plan = dev.frostguard.engine.squads.SquadPlanner.plan(read);
                ArrayNode squads = (ArrayNode) snapshot.get("savedSquads");
                for (var squad : squads) {
                    int slot = squad.get("slot").asInt();
                    ArrayNode names = mapper.createArrayNode();
                    if (slot == 8) plan.squads().get(0).heroes().forEach(names::add);
                    else if (slot >= 1 && slot <= 3 && !plan.squads().get(slot).heroes().isEmpty()) names.add(plan.squads().get(slot).heroes().get(0));
                    else if (slot == 4 || slot == 5) { }
                    else continue;
                    ObjectNode node = (ObjectNode) squad;
                    node.set("heroes", names);
                    node.put("state", "SAVED");
                    // Marksmen sized so the bear squads fit in the 190,078 at home.
                    long marks = slot == 8 ? 90_000 : slot == 4 || slot == 5 ? 0 : 30_000;
                    node.set("troops", mapper.createArrayNode().add(2_000).add(2_000).add(marks));
                    if (node.get("power") == null || node.get("power").isNull()) node.put("power", 62_000_000L);
                }
                ((ArrayNode) snapshot.get("problems")).removeAll();
            }
            if (scan == Scan.INCOMPLETE) {
                snapshot.putNull("troops");
                snapshot.set("savedSquads", mapper.createArrayNode());
                ArrayNode heroes = (ArrayNode) snapshot.get("heroes");
                while (heroes.size() > 3) heroes.remove(heroes.size() - 1);
                ArrayNode problems = mapper.createArrayNode();
                for (int card = 4; card <= 21; card++) problems.add("Hero card " + card + " could not be found again on the Heroes grid");
                problems.add("Chief Profile did not open, so troops and saved squads were not read");
                snapshot.set("problems", problems);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), snapshot);
        }
        // Remembered once: a test that loads the page several times must not take its own
        // temporary workspace for the original one and leave it behind for the next test.
        if (!workspaceRemembered) {
            previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            workspaceRemembered = true;
        }
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());

        FXMLLoader loader = new FXMLLoader(SquadsLayoutController.class.getResource("/layout/SquadsLayout.fxml"));
        SquadsLayoutController controller = new SquadsLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        ProfileAux profile = new ProfileAux(1L, "Default", "0", true, 50L, "", 0L);
        // The account's bear setup: lead on squad 8, hero joins on 1 to 3, troop-only joins on 4 and 5.
        profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, true);
        profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_RALLY_FLAG_INT, 8);
        profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "1,2,3");
        profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_JOIN_TROOP_FLAG_STRING, "4,5");
        profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "12-11-2026 19:00");
        controller.onProfileLoad(profile);
        return new View(root, loader.getNamespace());
    }

    private static Void render(View view, Path dir, String state, int width, int tab, String tabName) throws Exception {
        Files.createDirectories(dir);
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        TabPane tabs = (TabPane) view.namespace().get("tabsResults");
        // Chosen before the first style pass, so the tab pane is built showing this tab.
        tabs.getSelectionModel().select(tab);
        Scene scene = new Scene(view.root(), width, 1500);
        scene.getStylesheets().add(Objects.requireNonNull(
                SquadsLayoutController.class.getResource("/styles/style.css")).toExternalForm());
        view.root().applyCss();
        view.root().layout();
        File out = dir.resolve("squads-" + state + "-" + width + "-" + tabName + ".png").toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", out);
        return null;
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
