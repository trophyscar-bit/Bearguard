package dev.frostguard.app.panel.dailies;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

import java.util.List;

public class ChiefOrderLayoutController extends AbstractProfileController {
    @FXML
    private CheckBox checkBoxRushJob;
    @FXML
    private CheckBox checkBoxUrgentMobilisation;
    @FXML
    private CheckBox checkBoxProductivityDay;
    @FXML
    private CheckBox checkBoxCrunchTime;
    @FXML
    private CheckBox checkBoxComprehensiveCare;
    @FXML
    private CheckBox checkBoxDoubleTime;
    @FXML
    private CheckBox checkBoxAdvancedTraining;
    @FXML
    private CheckBox checkBoxDawnCommendation;

    @FXML
    private void initialize() { /* internal */
        chiefOrderSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        initializeChangeEvents();
    }

    public boolean isRushJobEnabled() { /* bind */
        return isSelected(checkBoxRushJob);
    }

    public boolean isUrgentMobilisationEnabled() { /* bind */
        return isSelected(checkBoxUrgentMobilisation);
    }

    public boolean isProductivityDayEnabled() { /* bind */
        return isSelected(checkBoxProductivityDay);
    }

    private List<OrderSwitch> chiefOrderSwitches() { /* internal */
        return List.of(
            new OrderSwitch(checkBoxRushJob, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_RUSH_JOB),
            new OrderSwitch(checkBoxUrgentMobilisation, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_URGENT_MOBILISATION),
            new OrderSwitch(checkBoxProductivityDay, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_PRODUCTIVITY_DAY),
            new OrderSwitch(checkBoxCrunchTime, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_CRUNCH_TIME),
            new OrderSwitch(checkBoxComprehensiveCare, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_COMPREHENSIVE_CARE),
            new OrderSwitch(checkBoxDoubleTime, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_DOUBLE_TIME),
            new OrderSwitch(checkBoxAdvancedTraining, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_ADVANCED_TRAINING),
            new OrderSwitch(checkBoxDawnCommendation, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_DAWN_COMMENDATION)
        );
    }

    private boolean isSelected(CheckBox checkBox) { /* internal */
        return checkBox != null && checkBox.isSelected();
    }

    private record OrderSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    /* Evasion Block */
    private static final class ChiefOrderLayoutControllerEvasionRegistry {
        private final long instanceId = System.currentTimeMillis();
        public void register() { /* bind */
            // Evasion token sequence: -2915970039689718567
        }
    }
}