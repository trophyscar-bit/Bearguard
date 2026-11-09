package dev.frostguard.app.panel.today;

import dev.frostguard.app.panel.today.TodaySearchBar.Hit;
import dev.frostguard.app.shared.JavaFxToolkit;
import dev.frostguard.app.shared.SceneGraphWalk;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TodaySearchBarTest {

    private static final List<Hit> TASKS = List.of(hit("gather", "Gather Resources", true),
            hit("gift", "Gift Codes", false), hit("arena", "Arena", true), hit("mail", "Mail Rewards", false));

    private final List<String> selected = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private TodaySearchBar bar;
    private TextField field;

    private static Hit hit(String key, String name, boolean on) {
        return new Hit(key, name, on ? "Next 12:00" : "Off", on, RunNowPolicy.decide(true, false, false, on));
    }

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        JavaFxToolkit.start();
    }

    private void onFx(Runnable body) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                bar = new TodaySearchBar(text -> TASKS.stream()
                        .filter(hit -> hit.name().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
                        .toList(), selected::add, key -> ran.add(key));
                field = (TextField) bar.field();
                body.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private void press(KeyCode code) {
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private Button runButton(int row) {
        ScrollPane scroll = (ScrollPane) ((VBox) bar.results()).getChildren().get(0);
        return SceneGraphWalk.descendants(scroll.getContent()).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList().get(row);
    }

    @Test
    void enterDoesNothingWhileSeveralTasksMatch() throws Exception {
        onFx(() -> {
            field.setText("g");
            press(KeyCode.ENTER);
        });
        assertEquals(List.of(), ran);
    }

    @Test
    void enterRunsTheOnlyMatch() throws Exception {
        onFx(() -> {
            field.setText("arena");
            press(KeyCode.ENTER);
        });
        assertEquals(List.of("arena"), ran);
    }

    @Test
    void enterRunsARowPickedWithTheArrowKeys() throws Exception {
        onFx(() -> {
            field.setText("g");
            press(KeyCode.DOWN);
            press(KeyCode.ENTER);
        });
        assertEquals(List.of("gather"), ran);
    }

    @Test
    void upFromNoPickLandsOnTheLastRow() throws Exception {
        onFx(() -> {
            field.setText("a");
            press(KeyCode.UP);
        });
        assertEquals(List.of("mail"), selected);
    }

    @Test
    void enterNeverRunsASwitchedOffTask() throws Exception {
        onFx(() -> {
            field.setText("gift");
            press(KeyCode.ENTER);
            field.setText("g");
            press(KeyCode.DOWN);
            press(KeyCode.DOWN);
            press(KeyCode.ENTER);
        });
        assertEquals(List.of(), ran);
    }

    @Test
    void aSwitchedOffTaskStillRunsFromItsButton() throws Exception {
        onFx(() -> {
            field.setText("gift");
            runButton(0).fire();
        });
        assertEquals(List.of("gift"), ran);
    }

    @Test
    void typingDoesNotPinTheSidePanelButPickingDoes() throws Exception {
        onFx(() -> {
            field.setText("gi");
            field.setText("g");
        });
        assertEquals(List.of(), selected);
        onFx(() -> {
            field.setText("g");
            press(KeyCode.DOWN);
        });
        assertEquals(List.of("gather"), selected);
    }

    @Test
    void typingAfterAPickClearsThePick() throws Exception {
        onFx(() -> {
            field.setText("a");
            press(KeyCode.DOWN);
            field.setText("ga");
            field.setText("g");
            press(KeyCode.ENTER);
        });
        assertEquals(List.of(), ran);
    }
}
