package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.schedule.StaminaWaitScheduler;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

import java.time.LocalDateTime;

// Orchestrates stamina tracking: OCR reads, regen delay computation,
// availability gating, item top-ups, and travel time parsing.
public class StaminaHelper {

    private static final int STAMINA_OCR_ATTEMPTS = 5;
    private static final long STAMINA_OCR_RETRY_MS = 200L;

    private final EmulatorController device;
    private final String deviceSlot;
    private final TapInteractionService taps;
    private final ResilientOcrExecutor<Integer> numberReader;
    private final StaminaService persistence;
    private final Long accountKey;
    private final ProfileContextLogger trace;
    private final MarchHelper marchSupport;
    private final String accountLabel;
    private final LoggingService centralLog;
    private final StaminaItemTopUpFlow itemTopUpFlow;
    private final HudStaminaUi hudStaminaUi;

    /** Reads Chief Stamina off the main HUD. A seam so tests can stand in for the screen. */
    interface HudStaminaUi {
        Integer readStamina(int attempts, long retryMs) throws Exception;
    }

    public StaminaHelper(EmulatorController emuManager, String emulatorNumber,
                         ResilientOcrExecutor<Integer> integerHelper,
                         AccountDescriptor profile, MarchHelper marchHelper) {
        this.device = emuManager;
        this.deviceSlot = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.numberReader = integerHelper;
        this.persistence = StaminaService.getServices();
        this.accountKey = profile.getId();
        this.trace = new ProfileContextLogger(StaminaHelper.class, profile);
        this.marchSupport = marchHelper;
        this.accountLabel = profile.getName();
        this.centralLog = LoggingService.obtain();
        this.itemTopUpFlow = new StaminaItemTopUpFlow(observed -> {
            int tracked = persistence.getCurrentStamina(accountKey);
            persistence.setStamina(accountKey, observed);
            emitInfo("Obtain-more dialog: synchronized tracked stamina " + tracked + " -> " + observed);
        });
        this.hudStaminaUi = (attempts, retryMs) -> numberReader.attemptRecognition(
                CommonGameAreas.HUD_STAMINA_OCR_AREA.topLeft(),
                CommonGameAreas.HUD_STAMINA_OCR_AREA.bottomRight(),
                attempts, retryMs,
                CommonOCRSettings.HUD_STAMINA_SETTINGS,
                StaminaHelper::isHudStaminaText,
                StaminaHelper::parseHudStamina);
    }

    StaminaHelper(StaminaService persistence, Long accountKey, HudStaminaUi hudStaminaUi) {
        this.device = null;
        this.deviceSlot = null;
        this.taps = null;
        this.numberReader = null;
        this.persistence = persistence;
        this.accountKey = accountKey;
        this.trace = null;
        this.marchSupport = null;
        this.accountLabel = "test";
        this.centralLog = null;
        this.itemTopUpFlow = null;
        this.hudStaminaUi = hudStaminaUi;
    }

    /**
     * Reads Chief Stamina off the main HUD and persists it. No taps and no navigation: every caller
     * has already reached the City or the World map, where the bar is always on screen. This replaced
     * a profile-and-dialog round trip -- two taps, two back presses and about 4.5 seconds of OCR --
     * that the October 2026 Chief Profile redesign broke outright, leaving stamina unknown on every
     * read and Intel rescheduling itself a minute later, around the clock.
     */
    public void updateStaminaFromHud() {
        long startMs = System.currentTimeMillis();
        try {
            Integer reading = hudStaminaUi.readStamina(STAMINA_OCR_ATTEMPTS, STAMINA_OCR_RETRY_MS);
            if (reading == null) {
                emitWarn("HUD stamina unreadable (elapsed " + (System.currentTimeMillis() - startMs)
                        + "ms); is the City or World map on screen?");
            } else {
                emitInfo("Stamina read: " + reading);
                persistence.setStamina(accountKey, reading);
            }
        } catch (Exception ex) {
            emitWarn("Stamina update error: " + ex.getMessage());
        }
    }

    static boolean isHudStaminaText(String text) {
        return text != null && text.trim().matches("[0-9][0-9,]*");
    }

    static Integer parseHudStamina(String text) {
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    /**
     * Uses Chief Stamina items until the profile holds at least {@code targetStamina}, opening the
     * Obtain-more dialog from the HUD stamina bar. The structured result distinguishes a
     * confirmed item shortage from transient OCR or UI failures so callers can schedule safely.
     *
     * @param itemReserve number of items never to spend
     */
    public StaminaTopUpResult topUpFromHud(int targetStamina, int itemReserve) {
        taps.tapInside(CommonGameAreas.HUD_STAMINA_BAR, 1, 200);
        pause(1000);

        StaminaTopUpResult result = useItemsInOpenDialog(targetStamina, itemReserve);
        // The dialog's own close, not Back: a Back press that lands on the World map is the game's
        // "quit the game?" prompt.
        taps.tapNear(CommonGameAreas.STAMINA_DIALOG_CLOSE, TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
        pause(800);
        return result;
    }

    /** Same, but for the dialog the game opens itself when a red deploy cost is pressed. */
    public StaminaTopUpResult refillFromOpenDialog(int targetStamina, int itemReserve) {
        StaminaTopUpResult result = useItemsInOpenDialog(targetStamina, itemReserve);
        emitInfo("Closing obtain-more dialog");
        taps.tapNear(CommonGameAreas.STAMINA_DIALOG_CLOSE, TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
        pause(800);
        return result;
    }

    private StaminaTopUpResult useItemsInOpenDialog(int targetStamina, int itemReserve) {
        var useButton = device.locatePattern(deviceSlot, dev.frostguard.api.configs.TemplatesEnum.STAMINA_ITEM_USE_BUTTON,
                CommonGameAreas.STAMINA_DIALOG_USE_BUTTON.topLeft(),
                CommonGameAreas.STAMINA_DIALOG_USE_BUTTON.bottomRight(), 85);
        if (!useButton.isFound()) {
            emitWarn("Chief Stamina Use button not found in the obtain-more dialog");
            return StaminaTopUpResult.uiNotFound();
        }

        StaminaTopUpResult result = itemTopUpFlow.topUp(new StaminaItemTopUpFlow.Dialog() {
            @Override
            public Integer readCurrentStamina() {
                return readDialogNumber(CommonGameAreas.STAMINA_DIALOG_CURRENT,
                        CommonOCRSettings.STAMINA_FRACTION_SETTINGS, "current stamina");
            }

            @Override
            public Integer readItemCount() {
                return readDialogNumber(CommonGameAreas.STAMINA_DIALOG_ITEM_COUNT,
                        CommonOCRSettings.SPENT_STAMINA_SETTINGS, "chief stamina item count");
            }

            @Override
            public boolean useItem() {
                var currentUseButton = device.locatePattern(
                        deviceSlot,
                        dev.frostguard.api.configs.TemplatesEnum.STAMINA_ITEM_USE_BUTTON,
                        CommonGameAreas.STAMINA_DIALOG_USE_BUTTON.topLeft(),
                        CommonGameAreas.STAMINA_DIALOG_USE_BUTTON.bottomRight(),
                        85);
                if (!currentUseButton.isFound()) {
                    emitWarn("Chief Stamina Use button disappeared during top-up");
                    return false;
                }
                taps.tapInside(currentUseButton);
                pause(600);
                return true;
            }
        }, targetStamina, itemReserve);
        logTopUpResult(result, targetStamina, itemReserve);
        return result;
    }

    private void logTopUpResult(StaminaTopUpResult result, int targetStamina, int itemReserve) {
        String evidence = "status=" + result.status()
                + " observed=" + result.observedStamina()
                + " target=" + targetStamina
                + " itemCount=" + result.itemCount()
                + " reserve=" + itemReserve
                + " needed=" + result.itemsNeeded()
                + " final=" + result.finalStamina();
        if (result.successful()) {
            emitInfo("Stamina top-up result: " + evidence);
        } else {
            emitWarn("Stamina top-up result: " + evidence);
        }
    }

    private Integer readDialogNumber(dev.frostguard.api.domain.AreaData area,
                                     dev.frostguard.api.domain.OcrSettingsData settings, String label) {
        Integer value = numberReader.attemptRecognition(area.topLeft(), area.bottomRight(), 3, 100L, settings,
                txt -> RegexNumberParser.conformsTo(txt, CommonOCRSettings.NUMBER_PATTERN),
                txt -> RegexNumberParser.extractByPattern(txt, CommonOCRSettings.NUMBER_PATTERN));
        emitInfo("Obtain-more dialog: " + label + " = " + (value == null ? "unreadable" : value));
        return value;
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void subtractStamina(Integer spent, boolean rally) {
        int deduction;
        if (spent != null) {
            deduction = spent;
        } else {
            deduction = rally ? 25 : 10;
        }
        emitDebug("Deducting " + deduction + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.subtractStamina(accountKey, deduction);
    }

    public void addStamina(Integer amount) {
        if (amount == null) return;
        emitDebug("Crediting " + amount + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.addStamina(accountKey, amount);
    }

    public int getCurrentStamina() {
        return persistence.getCurrentStamina(accountKey);
    }

    // Computes minutes needed for stamina to regenerate from current to target.
    public int staminaRegenerationTime(int current, int target) {
        int waitMinutes = Math.toIntExact(StaminaService.minutesToRegenerate(current, target));
        if (waitMinutes > 0) {
            emitDebug((target - current) + " points deficit → " + waitMinutes + " min wait");
        }
        return waitMinutes;
    }

    // Validates stamina and optionally march slots; reschedules on failure.
    // If verifyMarches is true, also checks march availability.
    public boolean checkStaminaAndMarchesOrReschedule(
            int min, int refresh, StaminaWaitScheduler scheduler) {
        return verifyReadiness(min, refresh, scheduler, true);
    }

    public boolean checkStaminaOrReschedule(
            int min, int refresh, StaminaWaitScheduler scheduler) {
        return verifyReadiness(min, refresh, scheduler, false);
    }

    private boolean verifyReadiness(int min, int refresh,
                                    StaminaWaitScheduler scheduler, boolean verifyMarches) {
        int level = persistence.getCurrentStamina(accountKey);
        emitInfo("Stamina check: " + level);

        if (level < min) {
            int regenMinutes = staminaRegenerationTime(level, refresh);
            LocalDateTime retry = LocalDateTime.now().plusMinutes(regenMinutes);
            scheduler.deferForStamina(min, refresh, retry);
            emitWarn("Insufficient (" + level + "/" + min + ") - retry " +
                    GameTimeUtils.formatCountdown(retry));
            return false;
        }

        if (verifyMarches && !marchSupport.checkMarchesAvailable()) {
            scheduler.reschedule(LocalDateTime.now().plusMinutes(1));
            emitWarn("No march slots - retry in 1 min");
            return false;
        }

        return true;
    }

    // ── logging shortcuts ────────────────────────────────────────────

    private void emitInfo(String msg) {
        String full = accountLabel + " - " + msg;
        if (trace != null) trace.info(full);
        if (centralLog != null) centralLog.emit(TpMessageSeverityEnum.INFO, "StaminaHelper", accountLabel, msg);
    }

    private void emitWarn(String msg) {
        String full = accountLabel + " - " + msg;
        if (trace != null) trace.warn(full);
        if (centralLog != null) centralLog.emit(TpMessageSeverityEnum.WARNING, "StaminaHelper", accountLabel, msg);
    }

    private void emitDebug(String msg) {
        String full = accountLabel + " - " + msg;
        if (trace != null) trace.debug(full);
        if (centralLog != null) centralLog.emit(TpMessageSeverityEnum.DEBUG, "StaminaHelper", accountLabel, msg);
    }
}
