package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.ExpertSkillItemEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.PriorityItemData;
import dev.frostguard.engine.config.PriorityConfigResolver;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dawn Academy, the Fire Crystal Age home of the Experts, in one pass:
 * <ol>
 * <li>Learning: keep the learning queue busy on the highest-priority unlocked skill, upgrading
 * any skill whose Learning XP bar is full first.</li>
 * <li>Trek: claim the free Trek Supplies (08:00 and 20:00 UTC) and, when ticked, the day's gem
 * buys, spend them on an Auto Trek with "Auto-manage options and mini-games" ticked, then do the
 * same with any Frontier Supplies.</li>
 * <li>Relationships: advance any Expert whose Stats tab offers Relationship Advancement (the
 * flame bubble with a green arrow over the Academy) while their shards cover it.</li>
 * <li>Gifts: give every Compass to the chosen Expert once that Expert lives in the Academy.</li>
 * <li>Endless Wayfarer: claim the free reward track.</li>
 * </ol>
 * The only gem button tapped is the daily Trek Supply buy, when ticked, and only at
 * {@value #MAX_SUPPLY_PRICE} gems or less. Finish on the learning dialog and the paid Wayfarer
 * track are never tapped.
 */
public class DawnAcademyRoutine extends DelayedTask {

    /** Learning XP accrues at one point per second whichever duration is picked (2h = 7,200). */
    static final List<LearningOption> LEARNING_OPTIONS = List.of(
            new LearningOption(Duration.ofMinutes(10), new PointData(102, 662)),
            new LearningOption(Duration.ofHours(2), new PointData(390, 662)),
            new LearningOption(Duration.ofHours(10), new PointData(102, 740)),
            new LearningOption(Duration.ofHours(23), new PointData(390, 740)));
    /** Banking on a full bar: short, so a skill that needs XP gets the queue back soon. */
    static final LearningOption BANK_OPTION = LEARNING_OPTIONS.get(1);

    // "4,200/7,200" on the card; "4,200+7,200/7,200" on the dialog, where the middle number is the gain.
    private static final Pattern FRACTION = Pattern.compile("(\\d[\\d,.]*)(?:\\+[\\d,.]+)?/(\\d[\\d,.]*)");
    /** The game's white digits on dark or coloured bars read cleanly only with this preset. */
    private static final OcrSettingsData WHITE_TEXT = OcrSettingsData.forWhiteTextOnDark();
    static final int MAX_SUPPLY_PRICE = 1_000;
    private static final int DAILY_SUPPLY_BUYS = 4;
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    // City sidebar, Expert row: offsets from the row's book icon.
    private static final int ROW_STATUS_DY_TOP = 10;
    private static final int ROW_STATUS_DY_BOTTOM = 42;
    private static final int ROW_ARROW_X = 405;
    private static final int ROW_ARROW_DY = 12;

    // Expert screen.
    private static final PointData BACK_ARROW = new PointData(40, 38);
    private static final PointData SKILLS_TAB = new PointData(530, 1235);
    private static final PointData NEXT_EXPERT_ARROW = new PointData(671, 650);
    private static final int[] SKILL_SLOT_X = {88, 270, 448, 625};
    private static final int SKILL_SLOT_Y = 1075;

    // Learn Skills dialog.
    private static final PointData LEARN_DIALOG_CLOSE = new PointData(665, 310);
    private static final PointData LEARN_LABEL_TOP_LEFT = new PointData(440, 928);
    private static final PointData LEARN_LABEL_BOTTOM_RIGHT = new PointData(640, 962);

    // Expert Stats tab and the gift screen.
    private static final PointData STATS_TAB = new PointData(190, 1240);
    private static final PointData COMPASS_CARD = new PointData(137, 860);
    private static final PointData GIFT_SLIDER_START = new PointData(150, 1088);
    private static final PointData GIFT_SLIDER_END = new PointData(395, 1088);
    private static final PointData GIFT_COUNT_TOP_LEFT = new PointData(470, 1068);
    private static final PointData GIFT_COUNT_BOTTOM_RIGHT = new PointData(590, 1108);
    private static final PointData GIFT_SEND_BUTTON = new PointData(530, 1208);
    private static final PointData GIFT_PLUS_BUTTON = new PointData(427, 1088);
    // The Affinity bar on the gift screen ("100/460" for Cyrille, 27 Oct).
    static final PointData GIFT_AFFINITY_TOP_LEFT = new PointData(150, 552);
    static final PointData GIFT_AFFINITY_BOTTOM_RIGHT = new PointData(595, 592);
    static final int COMPASS_AFFINITY = 10;
    static final int TOP_OFF_MAX_AFFINITY = 200;
    // Relationship advancement screen: the Advance button and the shard bar ("18/5") above it.
    private static final PointData ADVANCE_BUTTON_TOP_LEFT = new PointData(200, 1160);
    private static final PointData ADVANCE_BUTTON_BOTTOM_RIGHT = new PointData(520, 1260);
    static final PointData ADVANCE_SHARDS_TOP_LEFT = new PointData(195, 1098);
    static final PointData ADVANCE_SHARDS_BOTTOM_RIGHT = new PointData(520, 1132);
    private static final PointData ADVANCED_EXIT = new PointData(360, 1150);

    // Dawn Academy screen. With the queue idle, Learn only shows a guide hand pointing at an
    // Expert in their room; the Expert opens from a tap where the finger points.
    private static final PointData ACADEMY_LEARN_ICON = new PointData(57, 150);
    // Where the idle guide hand pointed on 27 Oct: the Expert standing in the training room.
    private static final PointData EXPERT_IN_TRAINING_ROOM = new PointData(395, 850);
    static final AreaData GUIDE_HAND_AREA = new AreaData(new PointData(0, 250), new PointData(719, 1100));
    static final int FINGERTIP_DX = -20;
    static final int FINGERTIP_DY = -5;

    // Trek landing (the screen with the Frontier Trek and Tundra Trek signposts).
    // The supply counter sits in the same place on the landing and on either route.
    private static final PointData TREK_COUNTER_TOP_LEFT = new PointData(512, 22);
    private static final PointData TREK_COUNTER_BOTTOM_RIGHT = new PointData(615, 60);
    private static final PointData FRONTIER_COUNTER_TOP_LEFT = new PointData(350, 22);
    private static final PointData FRONTIER_COUNTER_BOTTOM_RIGHT = new PointData(420, 60);
    private static final PointData TREK_SUPPLY_COUNTER = new PointData(560, 41);
    private static final PointData WAYFARER_ICON = new PointData(650, 140);
    private static final PointData CLAIM_TOP_LEFT = new PointData(495, 560);
    private static final PointData CLAIM_BOTTOM_RIGHT = new PointData(660, 650);
    private static final PointData NEXT_SUPPLY_TOP_LEFT = new PointData(505, 585);
    private static final PointData NEXT_SUPPLY_BOTTOM_RIGHT = new PointData(650, 625);
    // "Daily Limit: 1/4" counts the gem buys left today; at 0/4 the button becomes a reset timer.
    static final PointData BUYS_LEFT_TOP_LEFT = new PointData(500, 696);
    static final PointData BUYS_LEFT_BOTTOM_RIGHT = new PointData(662, 724);
    private static final PointData BUY_SUPPLIES_BUTTON = new PointData(575, 756);
    // The shared Purchase dialog: "Confirm to spend 1,000 Gems?". "Don't show again" stays unticked
    // so every buy shows its price.
    static final PointData PURCHASE_PRICE_TOP_LEFT = new PointData(160, 568);
    static final PointData PURCHASE_PRICE_BOTTOM_RIGHT = new PointData(560, 610);
    private static final PointData PURCHASE_CONFIRM_BUTTON = new PointData(360, 789);
    private static final PointData PURCHASE_CLOSE = new PointData(635, 447);
    private static final PointData BLANK_LOWER_SCREEN = new PointData(360, 1110);

    // Auto Trek dialog.
    private static final PointData AUTO_MANAGE_CHECKBOX = new PointData(140, 776);
    private static final PointData AUTO_TREK_START = new PointData(360, 852);
    private static final PointData AUTO_TREK_CLOSE = new PointData(665, 388);
    private static final PointData DIALOGUE_ADVANCE = new PointData(360, 400);

    private static final Duration TREK_POLL = Duration.ofSeconds(4);
    private static final Duration TREK_STALL = Duration.ofSeconds(90);
    private static final Duration MIN_RESCHEDULE = Duration.ofMinutes(5);
    private static final Duration MAX_RESCHEDULE = Duration.ofHours(12);

    private LocalDateTime nextRun;

    public DawnAcademyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        nextRun = GameTimeUtils.dailyResetTime().plusMinutes(5);

        Optional<Duration> learningLeft = openAcademyFromSidebar();
        if (learningLeft == null) {
            logWarning("Dawn Academy could not be opened from the City sidebar Expert row.");
            finish(LocalDateTime.now().plusMinutes(30));
            return;
        }

        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_LEARNING_BOOL)) {
            if (learningLeft.isPresent()) {
                logInfo("Learning queue busy for " + learningLeft.get().toMinutes() + " more minutes.");
                considerNextRun(LocalDateTime.now().plus(learningLeft.get()));
            } else {
                runLearning();
            }
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_ADVANCE_RELATIONSHIPS_BOOL)) {
            advanceRelationships();
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_COMPASSES_BOOL)) {
            giftCompasses();
        }
        if (!returnToAcademy()) {
            logWarning("Could not get back to the Dawn Academy screen; skipping Trek this pass.");
            finish(LocalDateTime.now().plusMinutes(30));
            return;
        }

        if (anyTrekWorkEnabled()) {
            runTrek();
        }
        pressBack();
        sleepTask(1500);
        finish(nextRun);
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Opens the City sidebar, finds the Expert row and taps it. Returns the learning time left
     * when the row shows a running timer, empty when it is idle, and null when the row or the
     * screen it leads to cannot be found. Leaves the game on the Expert screen or Dawn Academy.
     */
    private Optional<Duration> openAcademyFromSidebar() {
        try {
            marchHelper.openLeftMenuCitySection(true);
        } catch (IllegalStateException notOpened) {
            return null;
        }
        sleepTask(800);
        ImageSearchResultData row = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            row = templateSearchHelper.locatePattern(TemplatesEnum.LEFT_MENU_EXPERT_TRAINING_BUTTON,
                    SearchConfig.builder().withMaxAttempts(2).build());
            if (row.isFound()) {
                break;
            }
            swipe(new PointData(220, 850), new PointData(220, 350));
            sleepTask(700);
        }
        if (row == null || !row.isFound()) {
            marchHelper.closeLeftMenu();
            return null;
        }
        int iconTop = row.getMatchedArea().topLeft().getY();
        Duration left = durationHelper.attemptRecognition(
                new PointData(120, iconTop + ROW_STATUS_DY_TOP),
                new PointData(330, iconTop + ROW_STATUS_DY_BOTTOM),
                3, 200L, null, GameTimeUtils::isAcceptedFormat, GameTimeUtils::parseDuration);
        tapNear(new PointData(ROW_ARROW_X, iconTop + ROW_ARROW_DY), 6);
        sleepTask(2500);
        if (!isOnExpertScreen() && !isOnAcademy()) {
            return null;
        }
        return left == null || left.isZero() || left.isNegative() ? Optional.empty() : Optional.of(left);
    }

    private boolean returnToAcademy() {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (isOnAcademy()) {
                return true;
            }
            tapNear(BACK_ARROW, 4);
            sleepTask(2000);
        }
        return isOnAcademy();
    }

    private boolean openExpertFromAcademy() {
        tapNear(ACADEMY_LEARN_ICON, 6);
        sleepTask(1500);
        ImageSearchResultData hand = templateSearchHelper.locatePattern(TemplatesEnum.DAWN_ACADEMY_GUIDE_HAND,
                SearchConfig.builder()
                        .withCoordinates(GUIDE_HAND_AREA.topLeft(), GUIDE_HAND_AREA.bottomRight())
                        .withMaxAttempts(3)
                        .withDelay(500)
                        .build());
        PointData expert = hand.isFound() ? fingertip(hand.getMatchedArea()) : EXPERT_IN_TRAINING_ROOM;
        if (!hand.isFound()) {
            logInfo("No guide hand on the Academy; tapping the Expert in the training room.");
        }
        tapNear(expert, 4);
        sleepTask(2500);
        return isOnExpertScreen();
    }

    static PointData fingertip(AreaData hand) {
        return new PointData(hand.topLeft().getX() + FINGERTIP_DX, hand.topLeft().getY() + FINGERTIP_DY);
    }

    private boolean isOnAcademy() {
        return found(TemplatesEnum.DAWN_ACADEMY_TREK_BUTTON, 2);
    }

    private boolean isOnExpertScreen() {
        return currentExpert() != null;
    }

    private ExpertSkillItemEnum.Expert currentExpert() {
        for (ExpertSkillItemEnum.Expert expert : ExpertSkillItemEnum.Expert.values()) {
            TemplatesEnum badge = badgeOf(expert);
            if (badge != null && templateSearchHelper.locatePattern(badge, SearchConfig.builder()
                    .withCoordinates(new PointData(230, 0), new PointData(500, 80)).build()).isFound()) {
                return expert;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ learning

    private void runLearning() {
        if (!isOnExpertScreen()) {
            if (!openExpertFromAcademy()) {
                logWarning("Learn did not open an Expert; learning skipped this pass.");
                considerNextRun(LocalDateTime.now().plusMinutes(30));
                return;
            }
        }
        tapNear(SKILLS_TAB, 6);
        sleepTask(1200);

        // The queue never sits idle: first a skill whose bar still needs XP, then, when every
        // unlocked bar is full and waiting on Books of Knowledge, bank XP on the first of those.
        // XP past the bar carries over to the next level, so banked time is never wasted.
        Set<ExpertSkillItemEnum.Expert> present = expertsInAcademy();
        SkillTarget bankable = null;
        for (SkillTarget target : skillTargets()) {
            if (!present.contains(target.expert()) || !showExpert(target.expert())) {
                continue;
            }
            tapNear(SKILLS_TAB, 6);
            sleepTask(800);
            SkillOutcome outcome = learnSkill(target, false);
            if (outcome.started()) {
                considerNextRun(LocalDateTime.now().plus(outcome.duration()).plusMinutes(1));
                return;
            }
            if (outcome.bankable() && bankable == null) {
                bankable = target;
            }
        }
        if (bankable != null && showExpert(bankable.expert())) {
            tapNear(SKILLS_TAB, 6);
            sleepTask(800);
            SkillOutcome outcome = learnSkill(bankable, true);
            if (outcome.started()) {
                considerNextRun(LocalDateTime.now().plus(outcome.duration()).plusMinutes(1));
                return;
            }
        }
        logInfo("No skill could start learning; checking again in 6 hours.");
        considerNextRun(LocalDateTime.now().plusHours(6));
    }

    /** Steps through the Experts with the arrow and returns every one that lives in the Academy. */
    private Set<ExpertSkillItemEnum.Expert> expertsInAcademy() {
        Set<ExpertSkillItemEnum.Expert> present = EnumSet.noneOf(ExpertSkillItemEnum.Expert.class);
        for (int step = 0; step < ExpertSkillItemEnum.Expert.values().length + 1; step++) {
            ExpertSkillItemEnum.Expert shown = currentExpert();
            if (shown != null && !present.add(shown)) {
                break;
            }
            tapNear(NEXT_EXPERT_ARROW, 6);
            sleepTask(700);
        }
        logInfo("Experts in the Academy: " + present + ".");
        return present;
    }

    /** The configured priorities first, then every other slot of every Expert. */
    private List<SkillTarget> skillTargets() {
        List<SkillTarget> targets = new ArrayList<>();
        Set<ExpertSkillItemEnum> listed = EnumSet.noneOf(ExpertSkillItemEnum.class);
        for (PriorityItemData item : PriorityConfigResolver.activeRankings(profile,
                ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_PRIORITIES_STRING)) {
            try {
                ExpertSkillItemEnum skill = ExpertSkillItemEnum.valueOf(item.getIdentifier().toUpperCase());
                if (listed.add(skill)) {
                    targets.add(new SkillTarget(skill.expert(), skill.slotIndex(), item.getName()));
                }
            } catch (IllegalArgumentException unknown) {
                logWarning("Unknown skill priority " + item.getIdentifier() + "; ignored.");
            }
        }
        for (ExpertSkillItemEnum skill : ExpertSkillItemEnum.values()) {
            if (listed.add(skill)) {
                targets.add(new SkillTarget(skill.expert(), skill.slotIndex(),
                        skill.expert() + " skill " + skill.slotIndex()));
            }
        }
        return targets;
    }

    private boolean showExpert(ExpertSkillItemEnum.Expert wanted) {
        for (int step = 0; step < 8; step++) {
            ExpertSkillItemEnum.Expert shown = currentExpert();
            if (shown == wanted) {
                return true;
            }
            tapNear(NEXT_EXPERT_ARROW, 6);
            sleepTask(700);
        }
        return currentExpert() == wanted;
    }

    /**
     * Starts learning {@code target}. A full bar is skipped unless {@code bank} is set, in which
     * case it learns {@link #BANK_OPTION} anyway and the XP carries over to the next level.
     */
    private SkillOutcome learnSkill(SkillTarget target, boolean bank) {
        tapNear(new PointData(SKILL_SLOT_X[target.slot() - 1], SKILL_SLOT_Y), 8);
        sleepTask(1000);
        if (found(TemplatesEnum.DAWN_ACADEMY_SKILL_UNLOCK_BUTTON, 1)) {
            logInfo(target.label() + " is locked.");
            return SkillOutcome.notStarted();
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_UPGRADE_SKILLS_BOOL)) {
            for (int upgrades = 0; upgrades < 5; upgrades++) {
                ImageSearchResultData upgrade = templateSearchHelper.locatePattern(
                        TemplatesEnum.DAWN_ACADEMY_UPGRADE_SKILL_BUTTON, SearchConfig.builder().build());
                if (!upgrade.isFound()) {
                    break;
                }
                logInfo("Upgrading " + target.label() + ".");
                tapInside(upgrade);
                sleepTask(2500);
            }
        }
        ImageSearchResultData speedup = templateSearchHelper.locatePattern(
                TemplatesEnum.EXPERT_TRAINING_SPEEDUP_ICON, SearchConfig.builder().build());
        if (speedup.isFound()) {
            // Another pass started it; the sidebar timer was simply unreadable.
            int top = speedup.getMatchedArea().topLeft().getY();
            Duration left = durationHelper.attemptRecognition(new PointData(535, top + 64),
                    new PointData(660, top + 94), 3, 200L, null,
                    GameTimeUtils::isAcceptedFormat, GameTimeUtils::parseDuration);
            logInfo(target.label() + " is already learning.");
            return SkillOutcome.started(left == null ? Duration.ofHours(1) : left);
        }
        ImageSearchResultData learn = templateSearchHelper.locatePattern(
                TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON, SearchConfig.builder().withMaxAttempts(2).build());
        if (!learn.isFound()) {
            logInfo(target.label() + " has no Learn button (maxed or locked).");
            return SkillOutcome.notStarted();
        }
        int learnTop = learn.getMatchedArea().topLeft().getY();
        long[] xp = parseFraction(stringHelper.attemptRecognition(new PointData(115, learnTop + 8),
                new PointData(535, learnTop + 44), 3, 200L, WHITE_TEXT,
                text -> parseFraction(text) != null, String::trim));
        if (xp == null) {
            logWarning("Learning XP of " + target.label() + " was unreadable; not starting it blind.");
            return SkillOutcome.notStarted();
        }
        boolean full = xp[0] >= xp[1];
        if (full && !bank) {
            logInfo(target.label() + " has full Learning XP (" + xp[0] + "/" + xp[1]
                    + ") but could not be upgraded; it needs more Books of Knowledge.");
            return SkillOutcome.bankableOnly();
        }
        LearningOption option = full ? BANK_OPTION : pickLearningOption(xp[0], xp[1]);

        tapInside(learn);
        sleepTask(1200);
        if (!found(TemplatesEnum.DAWN_ACADEMY_LEARN_SKILLS_TITLE, 2)) {
            logWarning("Learn Skills dialog did not open for " + target.label() + ".");
            return SkillOutcome.notStarted();
        }
        tapNear(option.checkbox(), 6);
        sleepTask(700);
        Duration shown = durationHelper.attemptRecognition(LEARN_LABEL_TOP_LEFT, LEARN_LABEL_BOTTOM_RIGHT,
                3, 200L, WHITE_TEXT, GameTimeUtils::isAcceptedFormat, GameTimeUtils::parseDuration);
        if (shown != null && !shown.equals(option.duration())) {
            logWarning("Learn button shows " + shown + " after picking " + option.duration()
                    + "; closing without starting.");
            tapNear(LEARN_DIALOG_CLOSE, 4);
            sleepTask(800);
            return SkillOutcome.notStarted();
        }
        ImageSearchResultData confirm = templateSearchHelper.locatePattern(
                TemplatesEnum.DAWN_ACADEMY_LEARN_CONFIRM_BUTTON, SearchConfig.builder().withMaxAttempts(2).build());
        if (!confirm.isFound()) {
            tapNear(LEARN_DIALOG_CLOSE, 4);
            sleepTask(800);
            return SkillOutcome.notStarted();
        }
        tapInside(confirm);
        sleepTask(2000);
        if (found(TemplatesEnum.DAWN_ACADEMY_LEARN_SKILLS_TITLE, 1)) {
            logWarning("Learn Skills dialog stayed open for " + target.label() + "; the queue may be busy.");
            tapNear(LEARN_DIALOG_CLOSE, 4);
            sleepTask(800);
            return SkillOutcome.notStarted();
        }
        logInfo((full ? "Banking XP on " : "Learning ") + target.label() + " for " + format(option.duration())
                + " (XP " + xp[0] + "/" + xp[1] + (full ? ", bar full, waiting on Books)." : ", needs " + (xp[1] - xp[0]) + ")."));
        return SkillOutcome.started(option.duration());
    }

    /**
     * The shortest duration that fills the Learning XP bar, so the skill is ready to upgrade with
     * as little XP past the bar as the options allow. Past-the-bar XP carries over to the next
     * level (7,800/3,600 became 4,200/7,200 after an upgrade on 27 Oct), so the 23h option is only
     * picked when nothing shorter fills the bar.
     */
    static LearningOption pickLearningOption(long currentXp, long neededXp) {
        long remaining = Math.max(0, neededXp - currentXp);
        for (LearningOption option : LEARNING_OPTIONS) {
            if (option.duration().toSeconds() >= remaining) {
                return option;
            }
        }
        return LEARNING_OPTIONS.get(LEARNING_OPTIONS.size() - 1);
    }

    /** Reads "4,200/7,200" (or "4,200+7,200/7,200" on the dialog) as {current, needed}. */
    static long[] parseFraction(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = FRACTION.matcher(text.replace(" ", ""));
        if (!matcher.find()) {
            return null;
        }
        String current = matcher.group(1);
        String needed = matcher.group(2);
        try {
            long have = Long.parseLong(current.replaceAll("[,.]", ""));
            long need = Long.parseLong(needed.replaceAll("[,.]", ""));
            return need > 0 ? new long[]{have, need} : null;
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ gifts

    /**
     * Gives every Compass to the configured Expert. An Expert only takes gifts once settled in
     * the Academy (1,000 Affinity from treks); until then this logs and does nothing.
     */
    private void giftCompasses() {
        ExpertSkillItemEnum.Expert focus;
        try {
            String configured = profile.getConfig(ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_EXPERT_STRING, String.class);
            focus = ExpertSkillItemEnum.Expert.valueOf(configured == null ? "AGNES" : configured.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            logWarning("Unknown gift Expert in settings; gifting skipped.");
            return;
        }
        if (!reachExpertScreen()) {
            logWarning("No Expert could be opened from the Academy; gifting skipped this pass.");
            return;
        }
        tapNear(STATS_TAB, 6);
        sleepTask(1000);
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_GIFT_TOP_OFF_BOOL)) {
            for (ExpertSkillItemEnum.Expert expert : expertsInAcademy()) {
                if (expert != focus && showExpert(expert)) {
                    topOff(expert);
                }
            }
        }
        if (!showExpert(focus)) {
            logInfo(focus + " is not in the Academy yet; Compasses kept until " + focus + " reaches 1,000 Affinity.");
            return;
        }
        if (!openGiftScreen(focus)) {
            return;
        }
        tapNear(COMPASS_CARD, 8);
        sleepTask(600);
        swipe(GIFT_SLIDER_START, GIFT_SLIDER_END, 600);
        sleepTask(700);
        Integer chosen = readCount(GIFT_COUNT_TOP_LEFT, GIFT_COUNT_BOTTOM_RIGHT, WHITE_TEXT);
        if (chosen == null || chosen <= 0) {
            logInfo("No Compasses to give " + focus + ".");
        } else {
            logInfo("Gifting " + chosen + " Compasses to " + focus + ".");
            sendGift();
        }
        closeGiftScreen();
    }

    /**
     * Gives the Expert on screen just enough Compasses to reach the next Affinity level when that
     * is at most {@value #TOP_OFF_MAX_AFFINITY} Affinity away, before the rest go to the focus
     * Expert. A level that close costs a handful of Compasses and its stat is otherwise left
     * unclaimed.
     */
    private void topOff(ExpertSkillItemEnum.Expert expert) {
        if (!openGiftScreen(expert)) {
            return;
        }
        long[] affinity = parseFraction(stringHelper.attemptRecognition(GIFT_AFFINITY_TOP_LEFT,
                GIFT_AFFINITY_BOTTOM_RIGHT, 3, 200L, WHITE_TEXT, text -> parseFraction(text) != null, String::trim));
        int compasses = affinity == null ? 0 : topOffCompasses(affinity[0], affinity[1]);
        if (compasses > 0) {
            tapNear(COMPASS_CARD, 8);
            sleepTask(600);
            for (int tap = 0; tap < compasses; tap++) {
                tapNear(GIFT_PLUS_BUTTON, 4);
                sleepTask(250);
            }
            Integer chosen = readCount(GIFT_COUNT_TOP_LEFT, GIFT_COUNT_BOTTOM_RIGHT, WHITE_TEXT);
            if (chosen != null && chosen == compasses) {
                logInfo("Topping off " + expert + ": " + compasses + " Compasses for " + (affinity[1] - affinity[0])
                        + " Affinity to the next level (" + affinity[0] + "/" + affinity[1] + ").");
                sendGift();
            } else {
                logInfo("Not topping off " + expert + ": wanted " + compasses + " Compasses, the count reads "
                        + chosen + ".");
            }
        }
        closeGiftScreen();
    }

    /**
     * Compasses (+{@value #COMPASS_AFFINITY} each) needed to finish the Affinity level, or 0 when
     * the level is further than {@value #TOP_OFF_MAX_AFFINITY} away or the read is not a fraction.
     */
    static int topOffCompasses(long current, long needed) {
        long missing = needed - current;
        if (missing <= 0 || missing > TOP_OFF_MAX_AFFINITY) {
            return 0;
        }
        return (int) ((missing + COMPASS_AFFINITY - 1) / COMPASS_AFFINITY);
    }

    private boolean openGiftScreen(ExpertSkillItemEnum.Expert expert) {
        ImageSearchResultData giftButton = templateSearchHelper.locatePattern(
                TemplatesEnum.DAWN_ACADEMY_GIFT_OPEN_BUTTON, SearchConfig.builder().withMaxAttempts(2).build());
        if (!giftButton.isFound()) {
            logWarning("Gift button not found on " + expert + "'s Stats tab.");
            return false;
        }
        tapInside(giftButton);
        sleepTask(1500);
        if (!found(TemplatesEnum.DAWN_ACADEMY_SELECT_GIFT_TITLE, 2)) {
            logWarning("Gift screen did not open for " + expert + ".");
            return false;
        }
        return true;
    }

    private void sendGift() {
        tapNear(GIFT_SEND_BUTTON, 8);
        sleepTask(2500);
        // A level-up banner may cover the screen; a tap on the empty top half clears it.
        tapNear(new PointData(360, 300), 10);
        sleepTask(1000);
    }

    private void closeGiftScreen() {
        if (found(TemplatesEnum.DAWN_ACADEMY_SELECT_GIFT_TITLE, 1)) {
            tapNear(BACK_ARROW, 4);
            sleepTask(1200);
        }
    }

    private boolean reachExpertScreen() {
        if (isOnExpertScreen()) {
            return true;
        }
        return (isOnAcademy() || returnToAcademy()) && openExpertFromAcademy();
    }

    // ------------------------------------------------------------------ relationships

    /**
     * Advances every Expert whose Stats tab shows Relationship Advancement where Gift normally
     * sits. The game flags this with a flame bubble and green arrow over the Academy and the
     * Expert; until it is done the Gift button is hidden, so Compass gifting cannot run either.
     * Each advance costs that Expert's shards and is only tapped when the shard bar covers it.
     */
    private void advanceRelationships() {
        if (!reachExpertScreen()) {
            logWarning("No Expert could be opened from the Academy; relationships skipped this pass.");
            return;
        }
        tapNear(STATS_TAB, 6);
        sleepTask(1000);
        for (ExpertSkillItemEnum.Expert expert : expertsInAcademy()) {
            if (!showExpert(expert)) {
                continue;
            }
            for (int tier = 0; tier < 5 && advanceRelationship(expert); tier++) {
                sleepTask(1000);
            }
        }
    }

    /** One advance for the Expert on screen. True when it went through and another may follow. */
    private boolean advanceRelationship(ExpertSkillItemEnum.Expert expert) {
        ImageSearchResultData button = templateSearchHelper.locatePattern(
                TemplatesEnum.DAWN_ACADEMY_RELATIONSHIP_ADVANCE_BUTTON, SearchConfig.builder().build());
        if (!button.isFound()) {
            return false;
        }
        tapInside(button);
        sleepTask(1500);
        ImageSearchResultData advance = templateSearchHelper.locatePattern(TemplatesEnum.DAWN_ACADEMY_ADVANCE_BUTTON,
                SearchConfig.builder().withCoordinates(ADVANCE_BUTTON_TOP_LEFT, ADVANCE_BUTTON_BOTTOM_RIGHT)
                        .withMaxAttempts(2).build());
        if (!advance.isFound()) {
            logWarning("Relationship Advancement for " + expert + " did not show its Advance button.");
            tapNear(BACK_ARROW, 4);
            sleepTask(1500);
            return false;
        }
        long[] shards = parseFraction(stringHelper.attemptRecognition(ADVANCE_SHARDS_TOP_LEFT,
                ADVANCE_SHARDS_BOTTOM_RIGHT, 3, 200L, WHITE_TEXT, text -> parseFraction(text) != null, String::trim));
        if (shards == null || shards[0] < shards[1]) {
            logInfo(expert + "'s relationship can advance but the shards do not cover it ("
                    + (shards == null ? "unreadable" : shards[0] + "/" + shards[1]) + ").");
            tapNear(BACK_ARROW, 4);
            sleepTask(1500);
            return false;
        }
        tapInside(advance);
        sleepTask(2500);
        if (!found(TemplatesEnum.DAWN_ACADEMY_ADVANCED_TITLE, 3)) {
            logWarning("Advance for " + expert + " showed no result screen.");
            tapNear(BACK_ARROW, 4);
            sleepTask(1500);
            return false;
        }
        logInfo("Advanced " + expert + "'s relationship (shards " + shards[0] + "/" + shards[1] + ").");
        tapNear(ADVANCED_EXIT, 10);
        sleepTask(2000);
        return isOnExpertScreen();
    }

    // ------------------------------------------------------------------ trek

    private boolean anyTrekWorkEnabled() {
        return enabled(ConfigurationKeyEnum.DAWN_ACADEMY_CLAIM_SUPPLIES_BOOL)
                || enabled(ConfigurationKeyEnum.DAWN_ACADEMY_BUY_SUPPLIES_BOOL)
                || enabled(ConfigurationKeyEnum.DAWN_ACADEMY_TUNDRA_TREK_BOOL)
                || enabled(ConfigurationKeyEnum.DAWN_ACADEMY_FRONTIER_TREK_BOOL)
                || enabled(ConfigurationKeyEnum.DAWN_ACADEMY_WAYFARER_BOOL);
    }

    private void runTrek() {
        ImageSearchResultData trekButton = templateSearchHelper.locatePattern(
                TemplatesEnum.DAWN_ACADEMY_TREK_BUTTON, SearchConfig.builder().build());
        if (!trekButton.isFound()) {
            return;
        }
        tapInside(trekButton);
        sleepTask(3500);
        if (!isOnTrekLanding()) {
            logWarning("Trek landing did not open.");
            return;
        }

        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_CLAIM_SUPPLIES_BOOL)
                || enabled(ConfigurationKeyEnum.DAWN_ACADEMY_BUY_SUPPLIES_BOOL)) {
            collectSupplies();
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_TUNDRA_TREK_BOOL)) {
            Integer supplies = readCount(TREK_COUNTER_TOP_LEFT, TREK_COUNTER_BOTTOM_RIGHT, WHITE_TEXT);
            if (supplies != null && supplies > 0) {
                runAutoTrek("Tundra Trek", TemplatesEnum.DAWN_ACADEMY_TUNDRA_TREK_SIGN, supplies);
            } else {
                logInfo("No Trek Supplies to spend (" + supplies + ").");
            }
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_FRONTIER_TREK_BOOL)
                && found(TemplatesEnum.DAWN_ACADEMY_FRONTIER_TREK_SIGN, 1)) {
            // The white-text preset reads this counter's lone 0 as 8; the plain read gets it right.
            Integer supplies = readCount(FRONTIER_COUNTER_TOP_LEFT, FRONTIER_COUNTER_BOTTOM_RIGHT, null);
            if (supplies != null && supplies > 0) {
                runAutoTrek("Frontier Trek", TemplatesEnum.DAWN_ACADEMY_FRONTIER_TREK_SIGN, supplies);
            }
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_WAYFARER_BOOL)) {
            claimWayfarer();
        }
        tapNear(BACK_ARROW, 4);
        sleepTask(2500);
    }

    private boolean isOnTrekLanding() {
        return found(TemplatesEnum.DAWN_ACADEMY_TUNDRA_TREK_SIGN, 3);
    }

    /** Opens the supply panel, claims and buys as ticked, reads the next free claim and closes it. */
    private void collectSupplies() {
        tapNear(TREK_SUPPLY_COUNTER, 4);
        sleepTask(1500);
        if (!found(TemplatesEnum.DAWN_ACADEMY_CLAIM_FOR_FREE_TITLE, 2)) {
            logWarning("Trek Supplies panel did not open.");
            return;
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_CLAIM_SUPPLIES_BOOL)) {
            claimFreeSupplies();
        }
        if (enabled(ConfigurationKeyEnum.DAWN_ACADEMY_BUY_SUPPLIES_BOOL)
                && found(TemplatesEnum.DAWN_ACADEMY_CLAIM_FOR_FREE_TITLE, 1)) {
            buySupplies();
        }
        Duration next = durationHelper.attemptRecognition(NEXT_SUPPLY_TOP_LEFT, NEXT_SUPPLY_BOTTOM_RIGHT,
                3, 200L, WHITE_TEXT, GameTimeUtils::isAcceptedFormat, GameTimeUtils::parseDuration);
        if (next != null && !next.isNegative() && !next.isZero()) {
            considerNextRun(LocalDateTime.now().plus(next).plusMinutes(1));
        }
        tapNear(BLANK_LOWER_SCREEN, 6);
        sleepTask(1200);
    }

    /** Claims the free supplies if their button is up. Leaves the supply panel open. */
    private void claimFreeSupplies() {
        ImageSearchResultData claim = templateSearchHelper.locatePattern(TemplatesEnum.TUNDRA_TREK_CLAIM_BUTTON,
                SearchConfig.builder().withCoordinates(CLAIM_TOP_LEFT, CLAIM_BOTTOM_RIGHT).build());
        if (claim.isFound()) {
            logInfo("Claiming the free Trek Supplies.");
            tapInside(claim);
            sleepTask(2500);
            if (!found(TemplatesEnum.DAWN_ACADEMY_CLAIM_FOR_FREE_TITLE, 1)) {
                // The reward popup replaced the panel; dismiss it and reopen to read the next time.
                tapNear(BLANK_LOWER_SCREEN, 6);
                sleepTask(1200);
                tapNear(TREK_SUPPLY_COUNTER, 4);
                sleepTask(1500);
            }
        }
    }

    /**
     * Uses the gem buys left today, each only at {@value #MAX_SUPPLY_PRICE} gems or less. The
     * panel stays open after a buy and its counter drops, which is the proof the buy went
     * through. Leaves the supply panel open.
     */
    private void buySupplies() {
        Integer left = readBuysLeft();
        if (left == null) {
            logWarning("Could not read the Trek Supply buys left today.");
            return;
        }
        if (left == 0) {
            logInfo("No gem Trek Supply buys left today.");
            return;
        }
        for (int buy = 0; buy < DAILY_SUPPLY_BUYS && left > 0; buy++) {
            tapNear(BUY_SUPPLIES_BUTTON, 6);
            sleepTask(1500);
            if (!found(TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE, 2)) {
                logWarning("The Trek Supply purchase dialog did not open.");
                return;
            }
            Integer price = integerHelper.attemptRecognition(PURCHASE_PRICE_TOP_LEFT, PURCHASE_PRICE_BOTTOM_RIGHT,
                    3, 200L, null, text -> parsePrice(text) != null, DawnAcademyRoutine::parsePrice);
            if (price == null || price > MAX_SUPPLY_PRICE) {
                logWarning("Not buying Trek Supplies at " + price + " gems (limit " + MAX_SUPPLY_PRICE + ").");
                tapNear(PURCHASE_CLOSE, 4);
                sleepTask(1200);
                return;
            }
            tapNear(PURCHASE_CONFIRM_BUTTON, 6);
            sleepTask(2500);
            if (found(TemplatesEnum.GATHER_SPEED_PURCHASE_TITLE, 1)) {
                logWarning("The Trek Supply purchase dialog stayed open; closing it.");
                tapNear(PURCHASE_CLOSE, 4);
                sleepTask(1200);
                return;
            }
            Integer after = readBuysLeft();
            if (after == null || after >= left) {
                logWarning("Trek Supply buy not confirmed (" + left + " -> " + after + " left); stopping.");
                return;
            }
            logInfo("Bought 5 Trek Supplies for " + price + " gems; " + after + " buys left today.");
            left = after;
        }
    }

    private Integer readBuysLeft() {
        return integerHelper.attemptRecognition(BUYS_LEFT_TOP_LEFT, BUYS_LEFT_BOTTOM_RIGHT, 3, 200L, null,
                text -> parseBuysLeft(text) != null, DawnAcademyRoutine::parseBuysLeft);
    }

    /** "Daily Limit: 1/4" reads 1, the buys left today. */
    static Integer parseBuysLeft(String text) {
        long[] fraction = parseFraction(text);
        return fraction == null || fraction[0] > fraction[1] ? null : (int) fraction[0];
    }

    /** "Confirm to spend 1,000 Gems?" reads 1000. */
    static Integer parsePrice(String text) {
        return text == null ? null : parseCount(text.replace(".", ""));
    }

    private void runAutoTrek(String name, TemplatesEnum sign, int supplies) {
        ImageSearchResultData signpost = templateSearchHelper.locatePattern(sign, SearchConfig.builder().build());
        if (!signpost.isFound()) {
            return;
        }
        tapInside(signpost);
        sleepTask(6000);
        ImageSearchResultData auto = templateSearchHelper.locatePattern(TemplatesEnum.TUNDRA_TREK_AUTO_BUTTON,
                SearchConfig.builder().withMaxAttempts(5).withDelay(1000).build());
        if (!auto.isFound()) {
            logWarning(name + ": Auto button not found.");
            leaveTrekRoute();
            return;
        }
        tapInside(auto);
        sleepTask(1500);
        if (!found(TemplatesEnum.DAWN_ACADEMY_AUTO_TREK_TITLE, 2)) {
            logWarning(name + ": Auto Trek dialog did not open.");
            leaveTrekRoute();
            return;
        }
        if (found(TemplatesEnum.TUNDRA_TREK_CHECK_INACTIVE, 1)) {
            tapNear(AUTO_MANAGE_CHECKBOX, 4);
            sleepTask(700);
        }
        if (!found(TemplatesEnum.TUNDRA_TREK_CHECK_ACTIVE, 2)) {
            logWarning(name + ": could not tick Auto-manage options and mini-games; not starting.");
            tapNear(AUTO_TREK_CLOSE, 4);
            sleepTask(800);
            leaveTrekRoute();
            return;
        }
        logInfo(name + ": Auto Trek on " + supplies + " supplies, auto-managed.");
        tapNear(AUTO_TREK_START, 6);
        sleepTask(3000);
        waitForAutoTrek(name, supplies);
        leaveTrekRoute();
    }

    private void waitForAutoTrek(String name, int supplies) {
        LocalDateTime deadline = LocalDateTime.now().plusSeconds(Math.max(180, supplies * 25L));
        Integer last = supplies;
        LocalDateTime lastChange = LocalDateTime.now();
        while (LocalDateTime.now().isBefore(deadline)) {
            sleepTask(TREK_POLL.toMillis());
            Integer left = readCount(TREK_COUNTER_TOP_LEFT, TREK_COUNTER_BOTTOM_RIGHT, WHITE_TEXT);
            if (left != null && !left.equals(last)) {
                last = left;
                lastChange = LocalDateTime.now();
            }
            boolean idle = found(TemplatesEnum.DAWN_ACADEMY_NEXT_STOP_BUTTON, 1);
            if (idle && last != null && last == 0) {
                logInfo(name + ": all supplies spent.");
                return;
            }
            if (Duration.between(lastChange, LocalDateTime.now()).compareTo(TREK_STALL) > 0) {
                if (idle) {
                    logInfo(name + ": Auto Trek stopped with " + last + " supplies left.");
                    return;
                }
                // A story beat the auto-manager does not answer: skip or tap through it.
                ImageSearchResultData skip = templateSearchHelper.locatePattern(TemplatesEnum.TUNDRA_TREK_SKIP_BUTTON,
                        SearchConfig.builder().build());
                if (skip.isFound()) {
                    tapInside(skip);
                } else {
                    tapNear(DIALOGUE_ADVANCE, 10);
                }
                lastChange = LocalDateTime.now();
            }
        }
        logWarning(name + ": Auto Trek still running at the time limit (" + last + " supplies left).");
    }

    private void leaveTrekRoute() {
        for (int attempt = 0; attempt < 3 && !isOnTrekLanding(); attempt++) {
            tapNear(BACK_ARROW, 4);
            sleepTask(3000);
        }
    }

    private void claimWayfarer() {
        tapNear(WAYFARER_ICON, 6);
        sleepTask(2500);
        ImageSearchResultData claimAll = templateSearchHelper.locatePattern(TemplatesEnum.DAWN_ACADEMY_WAYFARER_CLAIM_ALL,
                SearchConfig.builder().withMaxAttempts(2).build());
        if (claimAll.isFound()) {
            logInfo("Claiming the free Endless Wayfarer rewards.");
            tapInside(claimAll);
            sleepTask(2500);
            tapNear(BLANK_LOWER_SCREEN, 6);
            sleepTask(1200);
        }
        leaveTrekRoute();
    }

    private Integer readCount(PointData topLeft, PointData bottomRight, OcrSettingsData settings) {
        return integerHelper.attemptRecognition(topLeft, bottomRight, 3, 200L, settings,
                text -> parseCount(text) != null, DawnAcademyRoutine::parseCount);
    }

    /** The supplies held: "9/100" reads 9, a bare "10" reads 10. */
    static Integer parseCount(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = FIRST_NUMBER.matcher(text.replace(",", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ shared

    private boolean found(TemplatesEnum template, int attempts) {
        return templateSearchHelper.locatePattern(template,
                SearchConfig.builder().withMaxAttempts(attempts).build()).isFound();
    }

    private boolean enabled(ConfigurationKeyEnum key) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return value != null && value;
    }

    private void considerNextRun(LocalDateTime candidate) {
        if (candidate != null && candidate.isBefore(nextRun)) {
            nextRun = candidate;
        }
    }

    private void finish(LocalDateTime when) {
        LocalDateTime earliest = LocalDateTime.now().plus(MIN_RESCHEDULE);
        LocalDateTime latest = LocalDateTime.now().plus(MAX_RESCHEDULE);
        LocalDateTime at = when.isBefore(earliest) ? earliest : when.isAfter(latest) ? latest : when;
        logInfo("Next Dawn Academy pass at " + at.format(DATETIME_FORMATTER) + ".");
        reschedule(at);
    }

    private static TemplatesEnum badgeOf(ExpertSkillItemEnum.Expert expert) {
        return switch (expert) {
            case AGNES -> TemplatesEnum.EXPERT_TRAINING_AGNES_BADGE;
            case CYRILLE -> TemplatesEnum.EXPERT_TRAINING_CYRILLE_BADGE;
            case HOLGER -> TemplatesEnum.EXPERT_TRAINING_HOLGER_BADGE;
            case ROMULUS -> TemplatesEnum.EXPERT_TRAINING_ROMULUS_BADGE;
            case BALDUR -> TemplatesEnum.EXPERT_TRAINING_BALDUR_BADGE;
            case FABIAN -> TemplatesEnum.EXPERT_TRAINING_FABIAN_BADGE;
        };
    }

    private static String format(Duration duration) {
        return duration.toHours() > 0 ? duration.toHours() + "h" : duration.toMinutes() + "m";
    }

    record LearningOption(Duration duration, PointData checkbox) {
    }

    private record SkillTarget(ExpertSkillItemEnum.Expert expert, int slot, String label) {
    }

    private record SkillOutcome(boolean started, Duration duration, boolean bankable) {
        static SkillOutcome started(Duration duration) {
            return new SkillOutcome(true, duration, false);
        }

        static SkillOutcome notStarted() {
            return new SkillOutcome(false, Duration.ZERO, false);
        }

        /** Not started because the bar is full; learning it anyway would bank XP. */
        static SkillOutcome bankableOnly() {
            return new SkillOutcome(false, Duration.ZERO, true);
        }
    }
}
