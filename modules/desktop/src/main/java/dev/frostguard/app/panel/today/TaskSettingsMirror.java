package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.FieldValidationPresenter;
import dev.frostguard.app.shared.PriorityListView;
import dev.frostguard.app.shared.UtcDateTimeEditor;
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
import java.util.function.BooleanSupplier;

/**
 * Builds a compact second set of controls wired to a settings page's real ones. Each page is one
 * JavaFX node and cannot appear in two places, while moving it would blank its hub; a control
 * bound to the real one keeps a single source of truth and saves through the page's own listeners.
 */
final class TaskSettingsMirror {

    /** @param dirty true while a text box holds typing that has not been saved to its real control */
    record Result(CheckBox toggle, List<Node> rows, BooleanSupplier dirty) {
    }

    private static final String TAB_CONTENT_CLASS = "tab-content-area";
    private static final String CARD_CLASS = "content-card";
    /** A longer label above a control is a description of the section, not its name. */
    private static final int MAX_HEADING_LENGTH = 40;

    private final Map<ToggleGroup, ToggleGroup> mirroredGroups = new HashMap<>();
    private final Map<Node, ConfigurationKeyEnum> registered;
    private final Map<ConfigurationKeyEnum, String> settingNames;
    private final Map<Node, String> labels;
    private final Map<String, Integer> captionUses = new HashMap<>();
    private final Map<String, Integer> headedCaptionUses = new HashMap<>();
    private final List<BooleanSupplier> dirtyChecks = new ArrayList<>();

    private TaskSettingsMirror(TaskSettingsIndex.Source source) {
        registered = source.keys();
        settingNames = source.names();
        labels = source.labels();
        for (Node control : source.controls()) {
            captionUses.merge(labelFor(control), 1, Integer::sum);
        }
        for (Node control : source.controls()) {
            headedCaptionUses.merge(headedCaption(control), 1, Integer::sum);
        }
    }

    static Result build(TaskSettingsIndex.Source source) {
        TaskSettingsMirror mirror = new TaskSettingsMirror(source);
        List<Node> rows = new ArrayList<>();
        for (Node control : source.controls()) {
            rows.add(mirror.rowFor(control));
        }
        CheckBox toggle = source.activation() instanceof CheckBox real ? mirrorCheckBox(real, "") : null;
        List<BooleanSupplier> dirtyChecks = mirror.dirtyChecks;
        return new Result(toggle, rows, () -> dirtyChecks.stream().anyMatch(BooleanSupplier::getAsBoolean));
    }

    private Node rowFor(Node control) {
        Node row;
        if (control instanceof CheckBox real) {
            row = mirrorCheckBox(real, captionFor(control));
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
        } else if (control instanceof UtcDateTimeEditor real) {
            row = labelled(control, real.compactMirror());
        } else {
            // A row per control keeps a new control type visible instead of quietly missing.
            row = new Label("Change this on the full settings page");
        }
        bindPresence(row, control);
        return row;
    }

    private Node labelled(Node control, Node mirror) {
        Label label = new Label(captionFor(control));
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

    private TextField mirrorTextField(TextField real) {
        TextField mirror = new TextField(real.getText());
        dirtyChecks.add(() -> !Objects.equals(mirror.getText(), real.getText()));
        mirror.setPromptText(real.getPromptText());
        // The page's error text is not in the panel, so a rejected value at least shows as invalid.
        real.getStyleClass().addListener((ListChangeListener<String>) change -> {
            if (real.getStyleClass().contains(FieldValidationPresenter.ERROR_CLASS)) {
                if (!mirror.getStyleClass().contains(FieldValidationPresenter.ERROR_CLASS)) {
                    mirror.getStyleClass().add(FieldValidationPresenter.ERROR_CLASS);
                }
            } else {
                mirror.getStyleClass().remove(FieldValidationPresenter.ERROR_CLASS);
            }
        });
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
            // A tab pane hides its unselected tabs, which says nothing about the option itself.
            if (!node.getStyleClass().contains(TAB_CONTENT_CLASS)) {
                chain.add(node.visibleProperty());
            }
        }
        BooleanBinding visible = Bindings.createBooleanBinding(
                () -> chain.stream().allMatch(ObservableValue::getValue), chain.toArray(new ObservableValue[0]));
        row.visibleProperty().bind(visible);
        row.managedProperty().bind(visible);
    }

    /** A caption repeated per squad relies on page headings the panel does not have, so it gains them. */
    private String captionFor(Node control) {
        String caption = labelFor(control);
        if (captionUses.get(caption) == 1) {
            return caption;
        }
        String headed = headedCaption(control);
        if (headedCaptionUses.get(headed) == 1) {
            return headed;
        }
        ConfigurationKeyEnum key = registered.get(control);
        String full = key == null ? null : settingNames.get(key);
        return full != null ? withoutTypeSuffix(full) : headed;
    }

    /** The control's caption after the headings above it on its page, such as "Land of Heroes, Squad 1: Infantry %". */
    private String headedCaption(Node control) {
        List<String> headings = new ArrayList<>();
        boolean ownCaptionSkipped = labels.containsKey(control) || (control instanceof Labeled labeled && hasText(labeled.getText()));
        for (Node node = control; node != null && node.getParent() instanceof Pane parent; node = parent) {
            String above = parent instanceof GridPane grid ? labelInGridRow(grid, node) : lastLabelBefore(parent, node);
            if (above != null && ownCaptionSkipped && above.length() <= MAX_HEADING_LENGTH) {
                headings.add(0, shortHeading(above));
            }
            ownCaptionSkipped |= above != null;
            if (parent.getStyleClass().contains(CARD_CLASS)) {
                cardTitle(parent).ifPresent(title -> headings.add(0, shortHeading(title)));
                break;
            }
        }
        return headings.isEmpty() ? labelFor(control) : String.join(", ", headings) + ": " + labelFor(control);
    }

    private static java.util.Optional<String> cardTitle(Pane card) {
        for (Node child : card.getChildren()) {
            if (child instanceof Pane header && header.getStyleClass().stream().anyMatch(name -> name.startsWith("header-"))) {
                return header.getChildren().stream()
                        .filter(Labeled.class::isInstance)
                        .map(node -> ((Labeled) node).getText())
                        .filter(TaskSettingsMirror::hasText)
                        .findFirst();
            }
        }
        return java.util.Optional.empty();
    }

    /** Headings carry a dash or bracket of description that a one-line caption has no room for. */
    private static String shortHeading(String heading) {
        return heading.replaceFirst("\\s*[\u2013\u2014].*$", "").replaceFirst("\\s*\\(.*\\)", "").trim();
    }

    private String labelFor(Node control) {
        if (labels.containsKey(control)) {
            return labels.get(control);
        }
        if (control instanceof Labeled labeled && hasText(labeled.getText())) {
            return labeled.getText();
        }
        String beside = labelBeside(control);
        if (beside != null) {
            return beside;
        }
        String named = settingNames.get(registered.get(control));
        return named == null ? "" : withoutTypeSuffix(named);
    }

    private static String withoutTypeSuffix(String name) {
        return name.replaceFirst(" (Int|Bool|String|Json)$", "");
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
