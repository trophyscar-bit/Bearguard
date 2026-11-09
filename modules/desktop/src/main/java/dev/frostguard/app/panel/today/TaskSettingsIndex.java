package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.panel.custom.CustomTasksLayoutController;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.PriorityListView;
import dev.frostguard.app.shared.SceneGraphWalk;
import dev.frostguard.app.shared.UtcDateTimeEditor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Works out which real settings controls belong to each task, from where they sit on their page. */
final class TaskSettingsIndex {

    /**
     * @param keys   the setting each listed control saves to; a control that saves through its own
     *               handler has no entry
     * @param labels captions for controls that have no caption on their page to read
     */
    record Source(String pageTitle, Map<Node, ConfigurationKeyEnum> keys, Map<ConfigurationKeyEnum, String> names,
                  Map<Node, String> labels, Node activation, List<Node> controls) {
    }

    private record Placement(String pageTitle, Node control) {
    }

    private record Page(String title, Parent root, List<Node> controls) {
    }

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<ConfigurationKeyEnum, Placement> placementByKey = new HashMap<>();
    private final Map<Node, ConfigurationKeyEnum> keyOfControl = new HashMap<>();
    private final Map<ConfigurationKeyEnum, String> nameOfKey = new HashMap<>();
    private final Set<ConfigurationKeyEnum> switchKeys = new HashSet<>();
    private final CustomTasksLayoutController customTasks;

    /**
     * @param eventsController owns the screens that were flattened out of the Events page's tabs,
     *                         which have no controller of their own
     */
    TaskSettingsIndex(Map<String, Object> controllers, Map<String, Parent> pageRoots, Object eventsController,
                      CustomTasksLayoutController customTasks) {
        this.customTasks = customTasks;
        for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
            if (task.getConfigKey() != null) {
                switchKeys.add(task.getConfigKey());
            }
        }
        pageRoots.forEach((title, root) -> {
            Object owner = controllers.getOrDefault(title, eventsController);
            if (owner instanceof AbstractProfileController controller) {
                addPage(title, root, controller);
            }
        });
    }

    private void addPage(String title, Parent root, AbstractProfileController controller) {
        Map<Node, ConfigurationKeyEnum> registered = controller.registeredControls();
        List<Node> controls = SceneGraphWalk.descendants(root).stream()
                .filter(node -> registered.containsKey(node) || isOwnHandlerOption(node))
                .toList();
        pages.put(title, new Page(title, root, controls));
        nameOfKey.putAll(controller.getRegisteredSettings());
        for (Node control : controls) {
            ConfigurationKeyEnum key = registered.get(control);
            if (key != null) {
                keyOfControl.put(control, key);
                placementByKey.putIfAbsent(key, new Placement(title, control));
            }
        }
    }

    /** A control that saves through its own handler rather than the page's key table. */
    private static boolean isOwnHandlerOption(Node node) {
        if (isInsideCompositeControl(node)) {
            return false;
        }
        return node instanceof UtcDateTimeEditor || node instanceof ComboBox<?> || node instanceof CheckBox
                || node instanceof RadioButton || (node instanceof TextField field && field.isEditable());
    }

    private static boolean isInsideCompositeControl(Node node) {
        for (Parent parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof PriorityListView || parent instanceof UtcDateTimeEditor
                    || parent instanceof ComboBoxBase<?> || parent instanceof Spinner<?>) {
                return true;
            }
        }
        return false;
    }

    Optional<Source> find(TpDailyTaskEnum task) {
        if (TaskSettingsCatalog.hasNoSettings(task)) {
            return Optional.empty();
        }
        ConfigurationKeyEnum switchKey = TaskSettingsCatalog.switchKey(task);
        Placement placement = switchKey == null ? null : placementByKey.get(switchKey);
        Node activation = placement == null ? null : placement.control();

        List<ConfigurationKeyEnum> exact = TaskSettingsCatalog.exactOptions(task);
        if (exact != null) {
            List<Node> controls = placed(exact);
            return titleFor(placement, controls).map(title -> source(title, activation,
                    withRestOfPage(controls, activation, title)));
        }

        String wholePage = TaskSettingsCatalog.wholePage(task);
        Page page = wholePage != null && pages.containsKey(wholePage) ? pages.get(wholePage)
                : placement == null ? null : pages.get(placement.pageTitle());
        if (page == null) {
            return Optional.empty();
        }
        List<Node> layoutControls = wholePage != null ? page.controls() : controlsOf(activation, page);
        return Optional.of(source(page.title(), activation,
                withRestOfPage(withCatalogChanges(task, layoutControls, activation), activation, page.title())));
    }

    /** The options on the card of one custom task; custom tasks are not per-profile. */
    Optional<Source> findCustom(String className) {
        if (customTasks == null) {
            return Optional.empty();
        }
        return customTasks.controlsFor(className).map(card -> {
            Map<Node, String> labels = new HashMap<>();
            card.options().forEach((label, field) -> labels.put(field, label));
            return new Source("Custom Tasks", Map.of(), Map.of(), labels, card.enabled(),
                    List.copyOf(card.options().values()));
        });
    }

    /** The task's own options first, so its quick-reference list starts with what it actually reads. */
    private List<Node> withRestOfPage(List<Node> own, Node activation, String title) {
        if (TaskSettingsCatalog.isSharedPage(title)) {
            return own;
        }
        List<Node> all = new ArrayList<>(own);
        pages.get(title).controls().stream()
                .filter(control -> control != activation && !own.contains(control))
                .forEach(all::add);
        return all;
    }

    private Source source(String title, Node activation, List<Node> controls) {
        return new Source(title, keyOfControl, nameOfKey, Map.of(), activation, controls);
    }

    private Optional<String> titleFor(Placement placement, List<Node> controls) {
        if (placement != null) {
            return Optional.of(placement.pageTitle());
        }
        if (controls.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(placementByKey.get(keyOfControl.get(controls.get(0))).pageTitle());
    }

    private List<Node> placed(List<ConfigurationKeyEnum> keys) {
        return keys.stream().map(placementByKey::get).filter(Objects::nonNull).map(Placement::control).toList();
    }

    private List<Node> withCatalogChanges(TpDailyTaskEnum task, List<Node> layoutControls, Node activation) {
        List<ConfigurationKeyEnum> excluded = TaskSettingsCatalog.excludedOptions(task);
        List<Node> controls = new ArrayList<>(layoutControls.stream()
                .filter(control -> control != activation)
                .filter(control -> !keyOfControl.containsKey(control) || !excluded.contains(keyOfControl.get(control)))
                .toList());
        placed(TaskSettingsCatalog.addedOptions(task)).stream()
                .filter(control -> !controls.contains(control) && control != activation)
                .forEach(controls::add);
        return controls;
    }

    /**
     * On pages built as one flat list, a task's options are the rows under its switch rather than a
     * block around it, so a control outside every block goes to the nearest switch above it. Only
     * controls above the first switch are shared by every task on the page.
     */
    private List<Node> controlsOf(Node taskSwitch, Page page) {
        Map<Node, Set<Node>> regions = new LinkedHashMap<>();
        for (Node candidate : switchesOn(page)) {
            regions.put(candidate, regionOf(candidate, page));
        }
        Set<Node> own = regions.get(taskSwitch);
        List<Node> controls = new ArrayList<>();
        Node nearestSwitch = null;
        for (Node control : page.controls()) {
            if (regions.containsKey(control)) {
                nearestSwitch = control;
            }
            boolean claimed = regions.values().stream().anyMatch(region -> region.contains(control));
            if (own.contains(control) || (!claimed && (nearestSwitch == null || nearestSwitch == taskSwitch))) {
                controls.add(control);
            }
        }
        return controls;
    }

    private List<Node> switchesOn(Page page) {
        return page.controls().stream().filter(control -> switchKeys.contains(keyOfControl.get(control))).toList();
    }

    /** The largest block around a switch that contains no other task's switch. */
    private Set<Node> regionOf(Node taskSwitch, Page page) {
        Set<Node> others = new HashSet<>(switchesOn(page));
        others.remove(taskSwitch);
        if (others.isEmpty()) {
            return new HashSet<>(SceneGraphWalk.descendants(page.root()));
        }
        Node region = taskSwitch;
        for (Parent parent = region.getParent(); parent != null && region != page.root(); parent = parent.getParent()) {
            if (SceneGraphWalk.descendants(parent).stream().anyMatch(others::contains)) {
                break;
            }
            region = parent;
        }
        return new HashSet<>(SceneGraphWalk.descendants(region));
    }
}
