package dev.frostguard.app.panel.training;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Troops -> Healing.
 *
 * <p>
 * Split out of the Training screen: healing injured troops is a separate
 * concern from training new ones, and it was already deliberately excluded
 * from the Training master toggle even while it shared that screen.
 */
public class HealingLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxHealInjured;

	@FXML
	private TextField textFieldHealIdleRecheck;

	@FXML
	private Label labelHealIdleRecheckError;

	@FXML
	private void initialize() {
		registerHealingSettings();
		initializeChangeEvents();
	}

	private void registerHealingSettings() {
		registerCheckBox(checkBoxHealInjured, ConfigurationKeyEnum.HEAL_INJURED_ENABLED_BOOL);
		registerTextField(textFieldHealIdleRecheck, labelHealIdleRecheckError,
				ConfigurationKeyEnum.HEAL_INJURED_IDLE_RECHECK_INT);
	}
}
