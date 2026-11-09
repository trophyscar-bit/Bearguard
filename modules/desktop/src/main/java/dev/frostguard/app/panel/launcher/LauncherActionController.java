package dev.frostguard.app.panel.launcher;

import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.QueueStateData;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.panel.profile.ProfileManagerLayoutController;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.listener.QueueStateListener;
import dev.frostguard.engine.service.ProdInstanceGuard;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public class LauncherActionController implements BotStateListener, QueueStateListener {

    private static final Logger logger = LoggerFactory.getLogger(LauncherActionController.class);

    private final LauncherLayoutController layoutController;
    private final ScheduleService scheduleService;
    private final Runnable launchEngine;
    private final Consumer<String> onStartRefused;
    private ProfileManagerLayoutController profileManagerLayoutController;

    public LauncherActionController(LauncherLayoutController launcherLayoutController) {
        this(Objects.requireNonNull(launcherLayoutController, "launcherLayoutController"),
                ScheduleService.obtain()::launchEngineAfterProductionCheck, launcherLayoutController::onBotStartRefused);
    }

    /** The two ends of {@link #startBot()} are parameters so its guard can be tested without an engine. */
    LauncherActionController(LauncherLayoutController launcherLayoutController, Runnable launchEngine,
                             Consumer<String> onStartRefused) {
        layoutController = launcherLayoutController;
        this.launchEngine = launchEngine;
        this.onStartRefused = onStartRefused;
        scheduleService = ScheduleService.obtain();
        scheduleService.addEngineObserver(this);
        scheduleService.addQueueObserver(this);
    }

    public void setProfileManagerController(ProfileManagerLayoutController profileManagerLayoutController) {
        this.profileManagerLayoutController = profileManagerLayoutController;
    }

    /** Runs on the start/stop worker thread, so the blocking production check stays off the FX thread. */
    public void startBot() {
        startBot(ProdInstanceGuard.startRefusal());
    }

    /** For a caller that already holds the production check's answer, so it is not asked twice. */
    public void startBot(String refusal) {
        if (refusal != null) {
            Platform.runLater(() -> onStartRefused.accept(refusal));
            return;
        }
        logger.info("Start guard passed: no production instance is running");
        launchEngine.run();
    }

    public void stopBot() {
        // Route GUI stop through dedicated GUI stop-behavior policy.
        scheduleService.haltEngineFromGui();
    }

    public void pauseAllQueues() {
        scheduleService.suspendEngine();
    }

    public void resumeAllQueues() {
        scheduleService.resumeEngine();
    }

    public void pauseQueue(ProfileAux profile) {
        withProfile(profile, selectedProfile -> scheduleService.suspendAccountQueue(selectedProfile.getId()));
    }

    public void resumeQueue(ProfileAux profile) {
        withProfile(profile, selectedProfile -> scheduleService.resumeAccountQueue(selectedProfile.getId()));
    }

    public void captureScreenshots() {
        // Hook retained for toolbar parity; capture work belongs in the emulator panel.
    }

    public void loadProfilesIntoComboBox() {
        withProfileManager(manager -> {
            ProfileRoster roster = ProfileRoster.from(manager);
            if (roster.isEmpty()) {
                manager.loadProfiles();
                Platform.runLater(this::updateProfileComboBox);
                return;
            }
            applyRoster(roster, SelectionPolicy.SELECT_FIRST_WHEN_EMPTY);
        });
    }

    public void updateProfileComboBox() {
        withProfileManager(manager -> {
            ProfileRoster roster = ProfileRoster.from(manager);
            if (!roster.isEmpty()) {
                applyRoster(roster, SelectionPolicy.PRESERVE_ONLY);
            }
        });
    }

    public void selectProfile(ProfileAux selectedProfile) {
        selectProfile(selectedProfile, true);
    }

    public void selectProfile(ProfileAux selectedProfile, boolean userTriggered) {
        withProfile(selectedProfile, profile -> {
            if (profileManagerLayoutController != null) {
                profileManagerLayoutController.setLoadedProfileId(profile.getId());
                profileManagerLayoutController.notifyProfileLoadListeners(profile);
                // Reported: only when the user actually picked this from the dropdown -
                // not on the startup fallback selection (userTriggered=false), which
                // should never silently flip which profile is enabled.
                if (userTriggered) {
                    profileManagerLayoutController.setOnlyEnabledProfile(profile);
                }
            }
            layoutController.onProfileLoad(profile);
        });
    }

    public void refreshProfileComboBox() {
        Platform.runLater(this::updateProfileComboBox);
    }

    @Override
    public void onEngineStateTransition(BotStateData botState) {
        Platform.runLater(() -> layoutController.onEngineStateTransition(botState));
    }

    @Override
    public void onQueueStateChanged(QueueStateData queueState) {
        Platform.runLater(() -> layoutController.onQueueStateChanged(queueState));
    }

    private void applyRoster(ProfileRoster roster, SelectionPolicy policy) {
        ProfileAux currentSelection = layoutController.getSelectedProfile();
        layoutController.updateComboBoxItems(roster.profiles());

        Optional<ProfileAux> retained = roster.findById(currentSelection);
        if (retained.isPresent()) {
            layoutController.selectProfileInComboBox(retained.get());
            return;
        }

        boolean shouldSelectFallback = policy == SelectionPolicy.SELECT_FIRST_WHEN_EMPTY || currentSelection != null;
        if (shouldSelectFallback) {
            ProfileAux firstProfile = roster.firstProfile();
            layoutController.selectProfileInComboBox(firstProfile);
            selectProfile(firstProfile, false);
        }
    }

    private void withProfileManager(Consumer<ProfileManagerLayoutController> action) {
        if (profileManagerLayoutController != null) {
            action.accept(profileManagerLayoutController);
        }
    }

    private void withProfile(ProfileAux profile, Consumer<ProfileAux> action) {
        if (profile != null) {
            action.accept(profile);
        }
    }

    private enum SelectionPolicy {
        PRESERVE_ONLY,
        SELECT_FIRST_WHEN_EMPTY
    }

    private record ProfileRoster(ObservableList<ProfileAux> profiles) {
        private static ProfileRoster from(ProfileManagerLayoutController manager) {
            return new ProfileRoster(manager.getProfiles());
        }

        private boolean isEmpty() {
            return profiles == null || profiles.isEmpty();
        }

        private ProfileAux firstProfile() {
            return profiles.getFirst();
        }

        private Optional<ProfileAux> findById(ProfileAux selectedProfile) {
            if (selectedProfile == null || selectedProfile.getId() == null) {
                return Optional.empty();
            }

            return profiles.stream()
                .filter(profile -> Objects.equals(profile.getId(), selectedProfile.getId()))
                .findFirst();
        }
    }
}
