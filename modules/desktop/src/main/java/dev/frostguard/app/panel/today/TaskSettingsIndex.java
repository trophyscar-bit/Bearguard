package dev.frostguard.app.panel.today;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SceneGraphWalk;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Works out which real settings controls belong to each task, from where they sit on their page. */
final class TaskSettingsIndex {

    record Source(String pageTitle, AbstractProfileController controller, Node activation, List<Node> controls) {
    }

    private record Placement(String pageTitle, Parent page, AbstractProfileController controller, Node control) {
    }

    private record Page(String title, Parent root, AbstractProfileController controller, List<Node> controls) {
    }

    /** Tasks whose settings are a whole page: the page has several switches but only one job. */
    private static final Map<TpDailyTaskEnum, String> WHOLE_PAGE = Map.of(
            TpDailyTaskEnum.GATHER_RESOURCES, "Gather",
            TpDailyTaskEnum.GATHER_BOOST, "Gather",
            TpDailyTaskEnum.RESOURCE_STOCKPILE_SCAN, "Gather",
            TpDailyTaskEnum.ALLIANCE_SHOP, "Alliance Shop",
            TpDailyTaskEnum.TRAINING_TROOPS, "Training");

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<ConfigurationKeyEnum, Placement> placementByKey = new HashMap<>();
    private final Set<ConfigurationKeyEnum> switchKeys = new HashSet<>();

    /**
     * @param eventsController owns the screens that were flattened out of the Events page's tabs,
     *                         which have no controller of their own
     */
    TaskSettingsIndex(Map<String, Object> controllers, Map<String, Parent> pageRoots, Object eventsController) {
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
        List<Node> controls = SceneGraphWalk.descendants(root).stream().filter(registered::containsKey).toList();
        pages.put(title, new Page(title, root, controller, controls));
        controls.forEach(control -> placementByKey.putIfAbsent(
                registered.get(control), new Placement(title, root, controller, control)));
    }

    Optional<Source> find(TpDailyTaskEnum task) {
        Placement placement = task.getConfigKey() == null ? null : placementByKey.get(task.getConfigKey());
        String wholePage = WHOLE_PAGE.get(task);
        if (wholePage != null && pages.containsKey(wholePage)) {
            Page page = pages.get(wholePage);
            Node activation = placement == null ? null : placement.control();
            return Optional.of(new Source(page.title(), page.controller(), activation,
                    without(page.controls(), activation)));
        }
        if (placement == null) {
            return Optional.empty();
        }
        Page page = pages.get(placement.pageTitle());
        return Optional.of(new Source(page.title(), page.controller(), placement.control(),
                without(controlsOf(placement.control(), page), placement.control())));
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

    private static List<Node> without(List<Node> controls, Node excluded) {
        return controls.stream().filter(control -> control != excluded).toList();
    }

    private List<Node> switchesOn(Page page) {
        Map<Node, ConfigurationKeyEnum> registered = page.controller().registeredControls();
        return page.controls().stream().filter(control -> switchKeys.contains(registered.get(control))).toList();
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
