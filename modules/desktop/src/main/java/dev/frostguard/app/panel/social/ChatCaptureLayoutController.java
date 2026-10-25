package dev.frostguard.app.panel.social;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SettingValidators;
import dev.frostguard.api.configs.ConfigurationKeyEnum;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import java.util.Objects;

/**
 * Chat capture settings. Chat is only captured now, for the Discord digest, so this panel edits the
 * capture and shows nothing of the chat itself.
 */
public class ChatCaptureLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableChatCapture;
    @FXML
    private CheckBox checkBoxChatIncludeWorld;
    @FXML
    private CheckBox checkBoxChatIncludeAlliance;
    @FXML
    private CheckBox checkBoxChatFilterNoise;
    @FXML
    private ComboBox<Integer> comboBoxChatFrequency;
    @FXML
    private CheckBox checkBoxChatScrollUntilOverlap;
    @FXML
    private ComboBox<Integer> comboBoxChatPassMax;
    @FXML
    private ComboBox<Integer> comboBoxChatCatchupMax;
    @FXML
    private CheckBox checkBoxChatReconcile;
    @FXML
    private TextField textFieldChatReconcileTime;
    @FXML
    private Label labelChatReconcileTimeError;
    @FXML
    private ComboBox<Integer> comboBoxChatReconcileMinutes;
    @FXML
    private ComboBox<Integer> comboBoxChatFrameCache;

    /** Keeps every screen for ever. Stored as a figure no real budget would reach. */
    private static final int UNLIMITED_FRAMES = -1;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxEnableChatCapture, ConfigurationKeyEnum.CHAT_CAPTURE_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeWorld, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_WORLD_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeAlliance, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL);
        checkBoxMappings.put(checkBoxChatFilterNoise, ConfigurationKeyEnum.CHAT_CAPTURE_FILTER_NOISE_BOOL);

        comboBoxChatFrequency.getItems().addAll(15, 30, 45, 60, 90, 120);
        comboBoxChatFrequency.setCellFactory(lv -> minutesCell());
        comboBoxChatFrequency.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatFrequency, ConfigurationKeyEnum.CHAT_CAPTURE_FREQUENCY_MINUTES_INT);

        checkBoxMappings.put(checkBoxChatScrollUntilOverlap, ConfigurationKeyEnum.CHAT_SCROLL_UNTIL_OVERLAP_BOOL);
        comboBoxChatPassMax.getItems().addAll(3, 5, 10, 15, 20, 30);
        comboBoxChatPassMax.setCellFactory(lv -> minutesCell());
        comboBoxChatPassMax.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatPassMax, ConfigurationKeyEnum.CHAT_PASS_MAX_MINUTES_INT);
        comboBoxChatCatchupMax.getItems().addAll(10, 15, 30, 45, 60, 90, 120);
        comboBoxChatCatchupMax.setCellFactory(lv -> minutesCell());
        comboBoxChatCatchupMax.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatCatchupMax, ConfigurationKeyEnum.CHAT_CATCHUP_MAX_MINUTES_INT);
        // The ceilings mean nothing when the walk is on a fixed clock, so they say so.
        comboBoxChatPassMax.disableProperty().bind(checkBoxChatScrollUntilOverlap.selectedProperty().not());
        comboBoxChatCatchupMax.disableProperty().bind(checkBoxChatScrollUntilOverlap.selectedProperty().not());

        checkBoxMappings.put(checkBoxChatReconcile, ConfigurationKeyEnum.CHAT_RECONCILE_ENABLED_BOOL);
        registerTimeTextField(textFieldChatReconcileTime, labelChatReconcileTimeError,
                ConfigurationKeyEnum.CHAT_RECONCILE_TIME_STRING,
                SettingValidators.localTime("Catch-up time"));
        comboBoxChatReconcileMinutes.getItems().addAll(5, 10, 15, 30, 60, 90, 120);
        comboBoxChatReconcileMinutes.setCellFactory(lv -> minutesCell());
        comboBoxChatReconcileMinutes.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatReconcileMinutes,
                ConfigurationKeyEnum.CHAT_RECONCILE_MINUTES_INT);
        textFieldChatReconcileTime.disableProperty().bind(checkBoxChatReconcile.selectedProperty().not());
        comboBoxChatReconcileMinutes.disableProperty().bind(checkBoxChatReconcile.selectedProperty().not());

        // Off first, and off by default. This writes pictures of the profile's chat to disk, which
        // is a thing somebody should choose rather than find out about.
        comboBoxChatFrameCache.getItems().addAll(0, 100, 250, 500, 1000, UNLIMITED_FRAMES);
        comboBoxChatFrameCache.setCellFactory(lv -> megabytesCell());
        comboBoxChatFrameCache.setButtonCell(megabytesCell());
        comboBoxMappings.put(comboBoxChatFrameCache, ConfigurationKeyEnum.CHAT_FRAME_CACHE_MB_INT);
        comboBoxChatFrameCache.valueProperty().addListener((obs, was, now) -> {
            if (now == null || now != UNLIMITED_FRAMES || Objects.equals(was, now)) {
                return;
            }
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("Keep every screen");
            warn.setHeaderText("Nothing will ever be deleted.");
            warn.setContentText("Screens are about 400 KB each and a pass reads a few hundred of "
                    + "them, so this grows by roughly a gigabyte a day and keeps going. They are "
                    + "pictures of your chat, and the only thing that will ever remove them is you."
                    + "\n\nSet a figure instead unless you have a reason to keep the lot.");
            warn.showAndWait();
        });

        initializeChangeEvents();
    }

    /** Reads the budget as a size, and zero as what it means rather than as "0 MB". */
    private ListCell<Integer> megabytesCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item == 0 ? "Don't keep any"
                            : item == UNLIMITED_FRAMES ? "INSANE - never delete"
                            : item + " MB (about " + item * 5 / 2 + " screens)");
                }
            }
        };
    }

    private ListCell<Integer> minutesCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item + " min");
            }
        };
    }
}
