package dev.frostguard.app.panel.deals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.kordamp.ikonli.javafx.FontIcon;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.deals.DealChannels;
import dev.frostguard.engine.deals.DealsStore;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;

/**
 * Deal Tracker page: the Discord deal channels, one post per tracked item, from {@link DealChannels}.
 *
 * <p>Polls the report files like the Upcoming Events page polls its cache: they change once a day,
 * reading them is cheap, and a push listener across the custom-task boundary buys nothing.</p>
 */
public class DealTrackerLayoutController {

    private static final int REFRESH_SECONDS = 60;
    private static final int TITLE_ICON_SIZE = 30;
    private static final int MARK_ICON_SIZE = 16;
    private static final int CHART_HEIGHT = 300;
    private static final String BEST_ICON = "mdi2s-star";
    private static final String BEST_COLOUR = "#f0b232";
    private static final String CLOSE_ICON = "mdi2c-check-bold";
    private static final String CLOSE_COLOUR = "#23a55a";
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    @FXML
    private VBox channelList;
    @FXML
    private VBox post;
    @FXML
    private ScrollPane postScroll;
    @FXML
    private Label labelScanStatus;

    private final DealsStore store = DealsStore.forWorkspace(WorkspacePaths.current().root());
    private Timeline refreshTimer;
    private DealChannels.Snapshot shown;
    private String selected = DealChannels.CHANNELS.get(0).id();

    @FXML
    private void initialize() {
        refresh();
        refreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(REFRESH_SECONDS), e -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();
    }

    @FXML
    private void handleRefresh() {
        shown = null;
        refresh();
    }

    public void stopAutoRefresh() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }

    /** A failure while building the page is shown on it; a blank page hides what broke. */
    private void refresh() {
        try {
            DealChannels.Snapshot snapshot = DealChannels.read(store.root());
            // Rebuilt only on a change, so the minute's poll does not jump the page back to its top.
            if (!snapshot.equals(shown)) {
                shown = snapshot;
                render();
            }
        } catch (RuntimeException failed) {
            labelScanStatus.setText("The Deal Tracker could not show the reports: "
                    + failed.getClass().getSimpleName() + " " + failed.getMessage());
        }
    }

    private void render() {
        channelList.getChildren().clear();
        post.getChildren().clear();
        if (shown.day() == null) {
            labelScanStatus.setText("No reports yet.");
            post.getChildren().add(key("The deal scan has not written a report to " + store.root() + " yet."));
            shown.problems().forEach(p -> post.getChildren().add(key(p)));
            return;
        }
        labelScanStatus.setText("Scan of " + SHORT_DAY.format(shown.day()) + " · " + shown.packCount() + " packs");
        for (DealChannels.Board board : shown.boards()) {
            channelList.getChildren().add(channelButton(board.channel()));
        }
        shown.boards().stream().filter(b -> b.channel().id().equals(selected)).findFirst().ifPresent(this::renderPost);
        shown.problems().forEach(p -> post.getChildren().add(key(p)));
    }

    private Button channelButton(DealChannels.Channel channel) {
        Button button = new Button("#  " + channel.id());
        button.getStyleClass().setAll("deal-channel");
        button.setMaxWidth(Double.MAX_VALUE);
        if (channel.id().equals(selected)) {
            button.getStyleClass().add("deal-channel-selected");
        }
        button.setOnAction(e -> {
            selected = channel.id();
            render();
            postScroll.setVvalue(0);
        });
        return button;
    }

    private void renderPost(DealChannels.Board board) {
        DealChannels.Channel channel = board.channel();
        Label title = new Label(channel.title());
        title.getStyleClass().add("deal-title");
        HBox header = new HBox(10, title);
        header.setAlignment(Pos.CENTER_LEFT);
        iconFor(channel.item()).ifPresent(icon -> header.getChildren().add(0, icon));
        post.getChildren().add(header);
        if (board.hold() != null) {
            post.getChildren().add(warning(board.hold()));
        }
        if (board.tracker() != null) {
            post.getChildren().addAll(heading(channel.heading()), trackerBlock(board));
        }
        if (board.packs().isEmpty()) {
            post.getChildren().add(key("No pack carries " + channel.item() + " in the latest scan."));
        } else {
            post.getChildren().addAll(heading("Today's packs · best first"), packsBlock(board),
                    heading("Where to buy"));
            board.places().forEach(place -> post.getChildren().add(placeLine(place)));
        }
        if (!board.unpriced().isEmpty()) {
            post.getChildren().add(warning("Price unreadable today: " + String.join(", ", board.unpriced())));
        }
        post.getChildren().add(legend(channel));
        if (board.chart().size() > 1) {
            post.getChildren().add(chart(board));
        }
    }

    private static GridPane trackerBlock(DealChannels.Board board) {
        DealChannels.Tracker tracker = board.tracker();
        int places = board.channel().places();
        GridPane grid = block();
        addRow(grid, 0, code("Today"), right(code(usd(tracker.today(), places))), trackerNote(tracker));
        addRow(grid, 1, code("10-day average"), right(code(usd(tracker.average(), places))));
        addRow(grid, 2, code("Lowest seen"), right(code(usd(tracker.lowest(), places))),
                code(SHORT_DAY.format(tracker.lowestDay())));
        return grid;
    }

    private static Label trackerNote(DealChannels.Tracker tracker) {
        return switch (tracker.trend()) {
            case CHEAPER -> styled("▼ cheaper than usual", "deal-cheaper");
            case PRICIER -> styled("▲ pricier than usual", "deal-pricier");
            case USUAL -> code("= about usual");
        };
    }

    private static GridPane packsBlock(DealChannels.Board board) {
        DealChannels.Channel channel = board.channel();
        GridPane grid = block();
        addRow(grid, 0, code(""), code("Pack"), right(code("Price")), right(code(channel.countColumn())),
                right(code(channel.perColumn())));
        int row = 1;
        for (DealChannels.PackRow pack : board.packs()) {
            addRow(grid, row++, mark(pack.mark()), code(pack.label()), right(code(usd(pack.price(), 2))),
                    right(code(pack.count())), right(code(usd(pack.perUnit(), channel.places()))), trend(pack.trend()));
        }
        return grid;
    }

    private static GridPane block() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("deal-block");
        grid.setMaxWidth(GridPane.USE_PREF_SIZE);
        return grid;
    }

    private static void addRow(GridPane grid, int row, Node... cells) {
        for (int column = 0; column < cells.length; column++) {
            grid.add(cells[column], column, row);
        }
    }

    private static Node right(Label label) {
        GridPane.setHalignment(label, HPos.RIGHT);
        return label;
    }

    private static Node mark(DealChannels.Mark mark) {
        return switch (mark) {
            case BEST -> markIcon(BEST_ICON, BEST_COLOUR);
            case CLOSE -> markIcon(CLOSE_ICON, CLOSE_COLOUR);
            case OTHER -> markIcon("mdi2m-minus", "#949ba4");
        };
    }

    private static FontIcon markIcon(String literal, String colour) {
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(MARK_ICON_SIZE);
        icon.setIconColor(Color.web(colour));
        return icon;
    }

    private static Label trend(DealChannels.Trend trend) {
        if (trend == null) {
            return code("");
        }
        return switch (trend) {
            case CHEAPER -> styled("▼", "deal-cheaper");
            case PRICIER -> styled("▲", "deal-pricier");
            case USUAL -> code("=");
        };
    }

    private static TextFlow placeLine(DealChannels.Place place) {
        Text bullet = new Text("•  ");
        bullet.getStyleClass().add("deal-text");
        Text name = new Text(place.label());
        name.getStyleClass().add("deal-text-bold");
        Text path = new Text(" · " + place.path());
        path.getStyleClass().add("deal-text");
        return new TextFlow(bullet, name, path);
    }

    private static VBox legend(DealChannels.Channel channel) {
        HBox marks = new HBox(6, markIcon(BEST_ICON, BEST_COLOUR), key("best price per " + channel.keyWord() + "   "),
                markIcon(CLOSE_ICON, CLOSE_COLOUR), key("close to it   "), key("▲▼ vs the 10-day average"));
        marks.setAlignment(Pos.CENTER_LEFT);
        VBox legend = new VBox(2, marks);
        if (!"fire-crystals".equals(channel.id())) {
            legend.getChildren().add(key("Each = the whole pack's price ÷ its " + channel.item()
                    + "; packs also carry other items"));
        }
        return legend;
    }

    private static LineChart<String, Number> chart(DealChannels.Board board) {
        int places = board.channel().places();
        NumberAxis prices = new NumberAxis();
        prices.setForceZeroInRange(false);
        prices.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return usd(value.doubleValue(), places);
            }

            @Override
            public Number fromString(String text) {
                return Double.valueOf(text.replace("$", ""));
            }
        });
        LineChart<String, Number> chart = new LineChart<>(new CategoryAxis(), prices);
        chart.getStyleClass().add("deal-chart");
        chart.setTitle("Cheapest " + board.channel().item());
        chart.setAnimated(false);
        chart.setPrefHeight(CHART_HEIGHT);
        XYChart.Series<String, Number> cheapest = new XYChart.Series<>();
        cheapest.setName("Cheapest that day");
        XYChart.Series<String, Number> average = new XYChart.Series<>();
        average.setName("10-day average");
        for (DealChannels.Point point : board.chart()) {
            String day = SHORT_DAY.format(point.day());
            cheapest.getData().add(new XYChart.Data<>(day, point.perUnit()));
            average.getData().add(new XYChart.Data<>(day, point.average()));
        }
        chart.getData().add(cheapest);
        chart.getData().add(average);
        return chart;
    }

    /** The library names a scaled template "Charm Guide@0.83.png"; any file of the item will do as its picture. */
    private Optional<ImageView> iconFor(String item) {
        Pattern name = Pattern.compile(Pattern.quote(item) + "(@[\\d.]+)?\\.png");
        List<Path> files;
        try (Stream<Path> listing = Files.list(store.itemIconsDir())) {
            files = listing.filter(p -> name.matcher(p.getFileName().toString()).matches()).toList();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(files.get(0))) {
            ImageView view = new ImageView(new Image(in));
            view.setFitHeight(TITLE_ICON_SIZE);
            view.setPreserveRatio(true);
            return Optional.of(view);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static Label heading(String text) {
        return styled(text, "deal-heading");
    }

    private static Label code(String text) {
        return styled(text, "deal-code");
    }

    private static Label warning(String text) {
        Label label = styled(text, "deal-hold");
        label.setWrapText(true);
        return label;
    }

    private static Label key(String text) {
        Label label = styled(text, "deal-key");
        label.setWrapText(true);
        return label;
    }

    private static Label styled(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String usd(double value, int places) {
        return String.format(Locale.US, "$%,." + places + "f", value);
    }
}
