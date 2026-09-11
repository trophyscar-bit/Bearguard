package dev.frostguard.app.panel.events;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

/** Global settings for the "Upcoming Events" sidebar calendar -- currently just its timezone. */
public class EventScheduleSettingsLayoutController {

    @FXML
    private ComboBox<String> comboBoxEventsTimezone;

    @FXML
    private void initialize() {
        comboBoxEventsTimezone.getItems().add(EventScheduleClock.SYSTEM);
        comboBoxEventsTimezone.getItems().addAll(ZoneId.getAvailableZoneIds().stream()
                .filter(id -> id.contains("/") || "UTC".equals(id))
                .sorted()
                .toList());
        comboBoxEventsTimezone.setCellFactory(lv -> timezoneCell());
        comboBoxEventsTimezone.setButtonCell(timezoneCell());
        comboBoxEventsTimezone.setVisibleRowCount(14);

        HashMap<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
        String stored = cfg == null
                ? EventScheduleClock.SYSTEM
                : cfg.getOrDefault(ConfigurationKeyEnum.EVENTS_LOCAL_TIMEZONE_STRING.name(), EventScheduleClock.SYSTEM);
        comboBoxEventsTimezone.setValue(stored);
        EventScheduleClock.useSetting(stored);
    }

    @FXML
    private void handleSave() {
        String selected = comboBoxEventsTimezone.getValue();
        ConfigService.obtain().writeGlobalSetting(ConfigurationKeyEnum.EVENTS_LOCAL_TIMEZONE_STRING, selected);
        EventScheduleClock.useSetting(selected);

        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Upcoming Events Calendar");
        info.setHeaderText(null);
        info.setContentText("Timezone saved.");
        info.showAndWait();
    }

    private ListCell<String> timezoneCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else if (EventScheduleClock.SYSTEM.equals(item)) {
                    setText("Same as this computer (" + ZoneId.systemDefault() + ")");
                } else {
                    setText(item + "   " + offsetOf(item));
                }
            }
        };
    }

    private static String offsetOf(String zoneId) {
        try {
            String offset = ZonedDateTime.now(ZoneId.of(zoneId)).getOffset().getId();
            return "Z".equals(offset) ? "UTC+00:00" : "UTC" + offset;
        } catch (RuntimeException notAZone) {
            return "";
        }
    }
}
