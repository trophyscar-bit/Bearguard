package dev.frostguard.app.panel.today;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * The "this task is running" marker: a tiny spinning icy globe, drawn natively with a lit sphere.
 * It spins only while it is visible in a showing window. Without 3D support it is the plain yellow dot.
 */
final class RunningIndicator extends StackPane {

    static final double SIZE = 12;
    static final double FALLBACK_SIZE = 9;
    private static final Duration TURN = Duration.seconds(4);
    private static final int TEX_W = 256;
    private static final int TEX_H = 128;

    private static Image sharedTexture;

    private final RotateTransition spin;

    RunningIndicator() {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            Region dot = new Region();
            dot.getStyleClass().addAll("today-dot", "today-dot-run");
            sizeTo(FALLBACK_SIZE);
            getChildren().add(dot);
            spin = null;
            return;
        }
        sizeTo(SIZE);

        Sphere globe = new Sphere(SIZE / 2 - 0.5, 24);
        PhongMaterial material = new PhongMaterial(Color.WHITE);
        material.setDiffuseMap(texture());
        material.setSpecularColor(Color.web("#bcd6ff", 0.6));
        material.setSpecularPower(24);
        globe.setMaterial(material);
        // Slight axial tilt so the spin reads as a planet.
        Group tilted = new Group(globe);
        tilted.getTransforms().add(new Rotate(-18, new Point3D(0, 0, 1)));

        PointLight sun = new PointLight(Color.web("#fff6e0"));
        sun.setTranslateX(-SIZE);
        sun.setTranslateY(-SIZE);
        sun.setTranslateZ(-SIZE * 2);
        AmbientLight fill = new AmbientLight(Color.web("#5a6a8a"));

        Group root = new Group(tilted, sun, fill);
        SubScene scene = new SubScene(root, SIZE, SIZE, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(100);
        camera.setFieldOfView(20);
        // Distance chosen so the sphere's diameter fills the viewport height.
        camera.setTranslateZ(-(SIZE / 2) / Math.tan(Math.toRadians(10)) - 1);
        scene.setCamera(camera);
        getChildren().add(scene);

        spin = new RotateTransition(TURN, globe);
        spin.setAxis(Rotate.Y_AXIS);
        spin.setFromAngle(0);
        spin.setToAngle(360);
        spin.setInterpolator(Interpolator.LINEAR);
        spin.setCycleCount(Animation.INDEFINITE);

        sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((oo, w1, w2) -> watchWindow(w2));
                watchWindow(newScene.getWindow());
            }
            refresh();
        });
        parentProperty().addListener((o, a, b) -> {
            watchAncestors();
            refresh();
        });
        watchAncestors();
        refresh();
    }

    private final javafx.beans.InvalidationListener onVisibilityChange = o -> {
        watchAncestors();
        refresh();
    };
    private java.util.List<Node> watched = new java.util.ArrayList<>();

    /** Re-listens to the visibility of this node and every ancestor, so hiding a tab or pane pauses the spin. */
    private void watchAncestors() {
        for (Node n : watched) {
            n.visibleProperty().removeListener(onVisibilityChange);
            n.parentProperty().removeListener(onVisibilityChange);
        }
        watched = new java.util.ArrayList<>();
        for (Node n = this; n != null; n = n.getParent()) {
            n.visibleProperty().addListener(onVisibilityChange);
            n.parentProperty().addListener(onVisibilityChange);
            watched.add(n);
        }
    }

    private void watchWindow(javafx.stage.Window window) {
        if (window != null) {
            window.showingProperty().addListener((o, a, b) -> refresh());
        }
        refresh();
    }

    private void sizeTo(double size) {
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
    }

    /** True when the 3D globe is in use rather than the flat dot. */
    boolean isGlobe() {
        return spin != null;
    }

    /** True while the globe is actually turning. */
    boolean isSpinning() {
        return spin != null && spin.getStatus() == Animation.Status.RUNNING;
    }

    /** Re-checks whether the globe should be turning; call after changing visibility of an ancestor. */
    void refresh() {
        if (spin == null) {
            return;
        }
        boolean show = isShownOnScreen();
        if (show && spin.getStatus() != Animation.Status.RUNNING) {
            spin.play();
        } else if (!show && spin.getStatus() == Animation.Status.RUNNING) {
            spin.pause();
        }
    }

    private boolean isShownOnScreen() {
        if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing()) {
            return false;
        }
        for (Node n = this; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** One procedural texture shared by every globe: pale continents on a deep blue sea. */
    private static synchronized Image texture() {
        if (sharedTexture == null) {
            WritableImage img = new WritableImage(TEX_W, TEX_H);
            PixelWriter pw = img.getPixelWriter();
            Color sea = Color.web("#14407e");
            Color seaDeep = Color.web("#0b2a5c");
            Color ice = Color.web("#eaf6ff");
            Color shore = Color.web("#9fcff0");
            for (int y = 0; y < TEX_H; y++) {
                double lat = (y + 0.5) / TEX_H;
                double polar = Math.abs(lat - 0.5) * 2;
                for (int x = 0; x < TEX_W; x++) {
                    double u = (double) x / TEX_W * Math.PI * 2;
                    // Seamless around the globe: sample the noise on a circle.
                    double n = fbm(Math.cos(u) * 1.6, Math.sin(u) * 1.6, lat * 3.2);
                    double land = n + polar * polar * 0.45;
                    Color c;
                    if (polar > 0.9 || land > 0.62) {
                        c = ice;
                    } else if (land > 0.55) {
                        c = shore;
                    } else {
                        c = sea.interpolate(seaDeep, Math.min(1, polar));
                    }
                    pw.setColor(x, y, c);
                }
            }
            sharedTexture = img;
        }
        return sharedTexture;
    }

    private static double fbm(double x, double y, double z) {
        double sum = 0;
        double amp = 0.5;
        double f = 1;
        for (int i = 0; i < 4; i++) {
            sum += amp * valueNoise(x * f, y * f, z * f);
            amp *= 0.5;
            f *= 2;
        }
        return sum / 0.9375;
    }

    private static double valueNoise(double x, double y, double z) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        double xf = smooth(x - xi);
        double yf = smooth(y - yi);
        double zf = smooth(z - zi);
        double v = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    double w = (dx == 0 ? 1 - xf : xf) * (dy == 0 ? 1 - yf : yf) * (dz == 0 ? 1 - zf : zf);
                    v += w * hash(xi + dx, yi + dy, zi + dz);
                }
            }
        }
        return v;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffff) / 65535.0;
    }
}
