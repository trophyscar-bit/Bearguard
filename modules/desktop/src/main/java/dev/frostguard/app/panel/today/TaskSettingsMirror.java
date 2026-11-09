package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.PriorityListView;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.controlsfx.control.CheckComboBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a compact second set of controls wired to a settings page's real ones. Each page is one
 * JavaFX node and cannot appear in two places, while moving it would blank its hub; a control
 * bound to the real one keeps a single source of truth and saves through the page's own listeners.
 */
final class TaskSettingsMirror {

    record Result(CheckBox toggle, List<Node> rows) {
    }

    private final Map<ToggleGroup, ToggleGroup> mirroredGroups = new HashMap<>();
    private final Map<Node, ConfigurationKeyEnum> registered;
    private final Map<ConfigurationKeyEnum, String> settingNames;

    private TaskSettingsMirror(TaskSettingsIndex.Source source) {
        registered = source.controller().registeredControls();
        settingNames = source.controller().getRegisteredSettings();
    }

    static Result build(TaskSettingsIndex.Source source) {
        TaskSettingsMirror mirror = new TaskSettingsMirror(source);
        List<Node> rows = new ArrayList<>();
        for (Node control : source.controls()) {
            Node row = mirror.rowFor(control);
            if (row != null) {
                rows.add(row);
            }
        }
        CheckBox toggle = source.activation() instanceof CheckBox real ? mirrorCheckBox(real, "") : null;
        return new Result(toggle, rows);
    }

    private Node rowFor(Node control) {
        Node row;
        if (control instanceof CheckBox real) {
            row = mirrorCheckBox(real, labelFor(control));
        } else if (control instanceof RadioButton real) {
            row = mirrorRadio(real);
        } else if (control instanceof TextField real) {
            row = labelled(control, mirrorTextField(real));
        } else if (control instanceof ComboBox<?> real) {
            row = labelled(control, mirrorComboBox(real));
        } else if (control instanceof CheckComboBox<?> real) {
            row = labelled(control, mirrorCheckCombo(real));
        } else if (control instanceof PriorityListView real) {
            row = labelled(control, new PriorityListView(real));
        } else {
            return null;
        }
        bindPresence(row, control);
        return row;
    }

    private Node labelled(Node control, Node mirror) {
        Label label = new Label(labelFor(control));
        label.setWrapText(true);
        label.getStyleClass().add("today-setting-label");
        mirror.disableProperty().bind(control.disabledProperty());
        VBox box = new VBox(3, label, mirror);
        box.setFillWidth(true);
        return box;
    }

    private static CheckBox mirrorCheckBox(CheckBox real, String text) {
        CheckBox mirror = new CheckBox(text);
        mirror.setWrapText(true);
        mirror.setMaxWidth(Double.MAX_VALUE);
        mirror.selectedProperty().bindBidirectional(real.selectedProperty());
        mirror.disableProperty().bind(real.disabledProperty());
        return mirror;
    }

    private RadioButton mirrorRadio(RadioButton real) {
        RadioButton mirror = new RadioButton(real.getText());
        mirror.setWrapText(true);
        mirror.setMaxWidth(Double.MAX_VALUE);
        // Without a group, clicking the selected radio would switch it off on the real page too.
        if (real.getToggleGroup() != null) {
            mirror.setToggleGroup(mirroredGroups.computeIfAbsent(real.getToggleGroup(), group -> new ToggleGroup()));
        }
        mirror.selectedProperty().bindBidirectional(real.selectedProperty());
        mirror.disableProperty().bind(real.disabledProperty());
        return mirror;
    }

    private static TextField mirrorTextField(TextField real) {
        TextField mirror = new TextField(real.getText());
        mirror.setPromptText(real.getPromptText());
        real.textProperty().addListener((observable, previous, text) -> {
            if (!Objects.equals(mirror.getText(), text)) {
                mirror.setText(text);
            }
        });
        Runnable commit = () -> {
            if (!Objects.equals(mirror.getText(), real.getText())) {
                real.setText(mirror.getText());
                // The page validates and saves from its own action handler, so this reuses both.
                real.fireEvent(new ActionEvent(real, real));
                mirror.setText(real.getText());
            }
        };
        mirror.setOnAction(event -> commit.run());
        mirror.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) {
                commit.run();
            }
        });
        return mirror;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ComboBox<?> mirrorComboBox(ComboBox<?> real) {
        ComboBox mirror = new ComboBox(real.getItems());
        mirror.setMaxWidth(Double.MAX_VALUE);
        mirror.setConverter(real.getConverter());
        if (real.getCellFactory() != null) {
            mirror.setCellFactory(real.getCellFactory());
            mirror.setButtonCell(real.getCellFactory().call(null));
        }
        mirror.valueProperty().bindBidirectional((javafx.beans.property.Property) real.valueProperty());
        return mirror;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CheckComboBox<?> mirrorCheckCombo(CheckComboBox<?> real) {
        CheckComboBox mirror = new CheckComboBox(real.getItems());
        mirror.setMaxWidth(Double.MAX_VALUE);
        mirror.setConverter(real.getConverter());
        copyChecks(real, mirror);
        boolean[] syncing = { false };
        real.getCheckModel().getCheckedIndices().addListener((ListChangeListener<Integer>) change -> {
            if (!syncing[0]) {
                syncing[0] = true;
                copyChecks(real, mirror);
                syncing[0] = false;
            }
        });
        mirror.getCheckModel().getCheckedIndices().addListener((ListChangeListener<Integer>) change -> {
            if (!syncing[0]) {
                syncing[0] = true;
                copyChecks(mirror, real);
                syncing[0] = false;
            }
        });
        return mirror;
    }

    private static void copyChecks(CheckComboBox<?> from, CheckComboBox<?> to) {
        for (int index = 0; index < from.getItems().size(); index++) {
            boolean checked = from.getCheckModel().isChecked(index);
            if (checked != to.getCheckModel().isChecked(index)) {
                if (checked) {
                    to.getCheckModel().check(index);
                } else {
                    to.getCheckModel().clearCheck(index);
                }
            }
        }
    }

    /** Follows the real control when the page hides it, for example behind an unticked option. */
    private static void bindPresence(Node row, Node control) {
        List<ObservableValue<Boolean>> chain = new ArrayList<>();
        for (Node node = control; node != null; node = node.getParent()) {
            chain.add(node.visibleProperty());
        }
        BooleanBinding visible = Bindings.createBooleanBinding(
                () -> chain.stream().allMatch(ObservableValue::getValue), chain.toArray(new ObservableValue[0]));
        row.visibleProperty().bind(visible);
        row.managedProperty().bind(visible);
    }

    private String labelFor(Node control) {
        if (control instanceof Labeled labeled && hasText(labeled.getText())) {
            return labeled.getText();
        }
        String beside = labelBeside(control);
        if (beside != null) {
            return beside;
        }
        String named = settingNames.get(registered.get(control));
        return named == null ? "" : named.replaceFirst(" (Int|Bool|String|Json)$", "");
    }

    private static String labelBeside(Node control) {
        for (Node node = control; node != null && node.getParent() instanceof Pane parent; node = parent) {
            String found = node.getParent() instanceof GridPane grid
                    ? labelInGridRow(grid, node)
                    : lastLabelBefore(parent, node);
            if (found != null) {
                return found;
            }
            if (parent.getChildren().size() > 3) {
                return null;
            }
        }
        return null;
    }

    private static String lastLabelBefore(Pane parent, Node node) {
        String label = null;
        for (Node sibling : parent.getChildren()) {
            if (sibling == node) {
                return label;
            }
            if (sibling instanceof Label candidate && isCaption(candidate)) {
                label = candidate.getText();
            }
        }
        return label;
    }

    private static String labelInGridRow(GridPane grid, Node node) {
        int row = Objects.requireNonNullElse(GridPane.getRowIndex(node), 0);
        for (Node sibling : grid.getChildren()) {
            if (sibling instanceof Label candidate && isCaption(candidate)
                    && Objects.requireNonNullElse(GridPane.getRowIndex(sibling), 0) == row) {
                return candidate.getText();
            }
        }
        return null;
    }

    private static boolean isCaption(Label label) {
        return hasText(label.getText()) && !label.getStyleClass().contains("setting-validation-message");
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
