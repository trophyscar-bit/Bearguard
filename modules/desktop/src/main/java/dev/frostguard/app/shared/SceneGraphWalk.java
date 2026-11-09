package dev.frostguard.app.shared;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a page in document order. ScrollPane, TitledPane and TabPane keep their content outside
 * the child list until a skin exists, so a plain child walk finds nothing inside a page that has
 * never been on screen.
 */
public final class SceneGraphWalk {

    private SceneGraphWalk() {
    }

    public static List<Node> descendants(Node root) {
        List<Node> nodes = new ArrayList<>();
        visit(root, nodes);
        return nodes;
    }

    private static void visit(Node node, List<Node> nodes) {
        if (node == null) {
            return;
        }
        nodes.add(node);
        if (node instanceof ScrollPane scrollPane) {
            visit(scrollPane.getContent(), nodes);
        } else if (node instanceof TitledPane titledPane) {
            visit(titledPane.getContent(), nodes);
        } else if (node instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) {
                visit(tab.getContent(), nodes);
            }
        } else if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(child -> visit(child, nodes));
        }
    }
}
