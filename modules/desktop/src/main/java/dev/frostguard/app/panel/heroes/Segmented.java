package dev.frostguard.app.panel.heroes;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** A row of choices where exactly one is picked, with the dangerous one styled red when picked. */
public final class Segmented extends HBox {

    private final ToggleGroup group = new ToggleGroup();
    private final ObjectProperty<String> value = new SimpleObjectProperty<>();
    private final List<ToggleButton> buttons = new ArrayList<>();

    public Segmented() {
        getStyleClass().add("segmented");
        setAlignment(Pos.CENTER);
        group.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null) {
                // A segmented control never ends up with nothing picked.
                if (was != null) group.selectToggle(was);
                return;
            }
            value.set((String) now.getUserData());
        });
        value.addListener((obs, was, now) -> {
            for (ToggleButton button : buttons) {
                if (button.getUserData().equals(now)) group.selectToggle(button);
            }
        });
    }

    /** The choices in order; {@code danger}, if not null, is the one that wears the red style when picked. */
    void setItems(List<String> items, String danger) {
        getChildren().clear();
        buttons.clear();
        for (String item : items) {
            ToggleButton button = new ToggleButton(item);
            button.setUserData(item);
            button.setToggleGroup(group);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinWidth(0);
            button.getStyleClass().add("segment");
            if (item.equals(danger)) button.getStyleClass().add("segment-danger");
            HBox.setHgrow(button, Priority.ALWAYS);
            buttons.add(button);
            getChildren().add(button);
        }
    }

    /** The same hint on every choice. */
    void hint(String text) {
        for (ToggleButton button : buttons) button.setTooltip(new Tooltip(text));
    }

    List<String> getItems() {
        return buttons.stream().map(b -> (String) b.getUserData()).toList();
    }

    ObjectProperty<String> valueProperty() {
        return value;
    }

    String getValue() {
        return value.get();
    }

    void setValue(String item) {
        value.set(item);
    }

    /** The button for one choice, so a test can read its style or press it. */
    ToggleButton button(String item) {
        return buttons.stream().filter(b -> b.getUserData().equals(item)).findFirst().orElseThrow();
    }
}
