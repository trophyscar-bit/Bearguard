package dev.frostguard.app.panel.training;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

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

	/**
	 * A fixed list rather than a free-typed field. The routine spreads each check three
	 * minutes either side of this setting, so anything at or under three minutes could
	 * schedule at zero; offering only safe values means it can't be entered at all.
	 */
	private static final int[] IDLE_RECHECK_OPTIONS = {5, 10, 15, 20, 30, 60, 90, 120};

	@FXML
	private ComboBox<Integer> comboBoxHealIdleRecheck;

	@FXML
	private void initialize() {
		registerHealingSettings();
		initializeChangeEvents();
	}

	private void registerHealingSettings() {
		registerCheckBox(checkBoxHealInjured, ConfigurationKeyEnum.HEAL_INJURED_ENABLED_BOOL);
		for (int minutes : IDLE_RECHECK_OPTIONS) {
			comboBoxHealIdleRecheck.getItems().add(minutes);
		}
		registerComboBox(comboBoxHealIdleRecheck, ConfigurationKeyEnum.HEAL_INJURED_IDLE_RECHECK_INT);
		comboBoxHealIdleRecheck.disableProperty().bind(checkBoxHealInjured.selectedProperty().not());
	}
}
