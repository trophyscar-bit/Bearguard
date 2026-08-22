package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.chat.ChatTranscriptStore;
import dev.frostguard.engine.chat.ChatTranslator;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.layout.ChatRowSegmenter;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Captures World, Alliance and Personal chat on a schedule and stores it as a readable transcript.
 *
 * <p><b>Rows are found before anything is read.</b> Reading the whole feed as one block and
 * splitting the result afterwards cannot recover message boundaries -- by then the reader has
 * already run several people's lines together, and live captures show exactly that: senders like
 * {@code Ww} and bodies carrying three separate messages. Every message instead owns one avatar
 * tile, so {@link ChatRowSegmenter} finds the tiles geometrically and each row's name strip and
 * bubble are read separately. Two tight reads per row cannot merge anything.
 *
 * <p><b>Frames do not survive the pass.</b> Each screenshot is roughly 394KB, and the schedule this
 * task runs -- around thirty screens across three channels every twenty minutes -- lands near 6,500
 * frames a day, about 2.5GB. The same content as text is a few megabytes before de-duplication and
 * far less after. So a frame is deleted the moment its rows are safely stored, and kept only when
 * storing failed and the image is the sole remaining evidence.
 *
 * <p><b>Translation is network-only.</b> Frostguard ships to people who did not ask for a
 * translation stack, so nothing is downloaded and no key is needed; see {@link ChatTranslator}.
 */
public class ChatCaptureRoutine extends DelayedTask {

    private static final int DEFAULT_FREQUENCY_MINUTES = 30;
    private static final int DEFAULT_SCROLL_BACK = 30;
    private static final int DEFAULT_RETENTION_DAYS = 30;

    /** Distinct phrases held before the least-used are dropped. Chat repeats heavily, so a modest
     *  cache absorbs most of the traffic a pass would otherwise put on the network. */
    private static final int TRANSLATION_CACHE_SIZE = 5000;

    /**
     * Consecutive screens yielding nothing new before the walk gives up.
     *
     * <p>Two was too eager. A screen can legitimately yield nothing -- a run of stickers, a couple
     * of unreadable bubbles, a timestamp divider -- and stopping there cuts the scroll-back short
     * while reporting a clean finish. The pass is bounded by scrollBack anyway, so the cost of
     * being generous here is a few extra screens, and the cost of being wrong is silently
     * capturing a fraction of the history.
     */
    private static final int BARREN_SCREENS_BEFORE_STOP = 6;

    /** Chat entry point: the globe/chat icon along the bottom of the World view. */
    private static final PointData CHAT_OPEN = new PointData(43, 1135);

    private static final PointData TAB_WORLD = new PointData(132, 116);
    private static final PointData TAB_ALLIANCE = new PointData(360, 117);
    private static final PointData TAB_PERSONAL = new PointData(588, 117);
    private static final PointData CHAT_CLOSE = new PointData(44, 40);

    /** Scrollable message area -- excludes the tab header and the compose bar. */
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1150;
    private static final int FEED_X = 360;

    /**
     * Reader configuration for a chat region.
     *
     * <p>English-only is deliberate and evidence-backed: adding chi_sim was net-negative live,
     * because CJK's much larger glyph set false-matches small UI icons and badges as characters,
     * putting stray glyphs into otherwise-Latin text. Foreign chat is handled downstream by
     * translating the read text rather than by widening the reader.
     */
    private static final OcrSettingsData CHAT_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    .language("eng")
                    .preserveLineBreaks(true)
                    .build();

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private int frequencyMinutes = DEFAULT_FREQUENCY_MINUTES;
    private boolean includeWorld = true;
    private boolean includeAlliance = true;
    private boolean includePersonal = false;
    private String mode = "TRANSCRIPT";
    private int scrollBack = DEFAULT_SCROLL_BACK;
    private int retentionDays = DEFAULT_RETENTION_DAYS;

    private ChatTranscriptStore store;
    private ChatTranslator translator;

    public ChatCaptureRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time -- the queue compares against LocalDateTime.now(); a UTC instant here would
        // silently defer the first run by the UTC offset.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "chat_capture";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    private void loadSettings() {
        Integer freq = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_FREQUENCY_MINUTES_INT, Integer.class);
        frequencyMinutes = freq != null && freq > 0 ? freq : DEFAULT_FREQUENCY_MINUTES;

        includeWorld = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_WORLD_BOOL, Boolean.class));
        includeAlliance = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL, Boolean.class));
        includePersonal = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_PERSONAL_BOOL, Boolean.class));

        String storedMode = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_MODE_STRING, String.class);
        mode = storedMode != null && !storedMode.isBlank() ? storedMode : "TRANSCRIPT";

        Integer back = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_SCROLL_BACK_INT, Integer.class);
        scrollBack = back != null && back > 0 ? back : DEFAULT_SCROLL_BACK;

        Integer keep = profile.getConfig(ConfigurationKeyEnum.CHAT_TRANSCRIPT_RETENTION_DAYS_INT, Integer.class);
        retentionDays = keep != null && keep > 0 ? keep : DEFAULT_RETENTION_DAYS;

        boolean translate = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_TRANSLATE_TO_ENGLISH_BOOL, Boolean.class));
        translator = new ChatTranslator(translate, TRANSLATION_CACHE_SIZE);
        store = new ChatTranscriptStore(baseDir(), ZoneId.systemDefault());
    }

    @Override
    protected void execute() {
        loadSettings();

        if (!includeWorld && !includeAlliance && !includePersonal) {
            logInfo("ChatCaptureRoutine | No channels selected; nothing to capture.");
            reschedule(LocalDateTime.now().plusMinutes(frequencyMinutes));
            setRecurring(true);
            return;
        }

        // Learn what previous runs already wrote before the first overlapping screen arrives,
        // otherwise a restart re-appends the whole scroll-back it is about to re-read.
        try {
            store.primeFromDisk();
            int purged = store.purgeOlderThan(retentionDays);
            if (purged > 0) {
                logInfo("ChatCaptureRoutine | Dropped " + purged + " transcript day(s) past the "
                        + retentionDays + "-day window.");
            }
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not read the existing transcript: " + e.getMessage());
        }

        logInfo("ChatCaptureRoutine | Opening chat (" + mode + ", " + scrollBack + " screens back).");
        tapNear(CHAT_OPEN);
        sleepTask(1200L);

        int totalNew = 0;
        if (includeWorld) {
            totalNew += captureChannel("world", TAB_WORLD);
        }
        if (includeAlliance) {
            totalNew += captureChannel("alliance", TAB_ALLIANCE);
        }
        if (includePersonal) {
            totalNew += captureChannel("personal", TAB_PERSONAL);
        }

        tapNear(CHAT_CLOSE);
        sleepTask(500L);

        int channelCount = (includeWorld ? 1 : 0) + (includeAlliance ? 1 : 0) + (includePersonal ? 1 : 0);
        String size;
        try {
            size = ChatTranscriptStore.humanSize(store.sizeBytes());
        } catch (IOException e) {
            size = "unknown";
        }
        logInfo("ChatCaptureRoutine | Stored " + totalNew + " new message(s) across " + channelCount
                + " channel(s). Transcript is now " + size + "; "
                + translator.cachedPhrases() + " phrase(s) cached.");

        setRecurring(true);
        reschedule(LocalDateTime.now().plusMinutes(frequencyMinutes));
    }

    /**
     * Walks one channel from the newest message backwards, storing what has not been seen before.
     *
     * @return how many genuinely new messages were stored
     */
    private int captureChannel(String channel, PointData tab) {
        tapNear(tab);
        sleepTask(1000L);

        int stored = 0;
        int barrenScreens = 0;

        for (int i = 0; i < scrollBack; i++) {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                logWarning("ChatCaptureRoutine | Could not capture a frame for " + channel
                        + "; stopping this channel.");
                break;
            }

            BufferedImage image = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
            List<ChatRowSegmenter.Row> rows = ChatRowSegmenter.segment(image);
            if (rows.isEmpty()) {
                logInfo("ChatCaptureRoutine | " + channel + ": no message rows on this screen.");
                swipeUpThroughHistory();
                continue;
            }

            List<ChatMessage> messages = ChatRowReader.read(
                    rows,
                    (topLeft, bottomRight) -> readRegion(frame, topLeft, bottomRight),
                    channel,
                    Instant.now(),
                    body -> translator.toEnglish(body));

            int fresh;
            try {
                fresh = store.append(messages);
                // Per-screen accounting. Without it a pass is a black box: the only way to tell a
                // successful walk from one that read nothing was the total at the end, which hides
                // where in the scroll-back it stopped finding anything.
                logInfo("ChatCaptureRoutine | " + channel + " screen " + (i + 1) + "/" + scrollBack
                        + ": " + rows.size() + " row(s), " + messages.size() + " readable, "
                        + fresh + " new.");
            } catch (IOException e) {
                // The frame is the only remaining copy of anything that failed to store, so keep it
                // and stop rather than scrolling past messages that were never written.
                logWarning("ChatCaptureRoutine | Could not write the transcript for " + channel
                        + ": " + e.getMessage());
                keepUnstoredFrame(channel, i, image);
                break;
            }
            stored += fresh;

            // Overlapping scroll-backs mean most screens are already stored. Two consecutive
            // screens with nothing new means this pass has reached history the previous pass
            // already covered, and going further only spends captures re-reading it.
            barrenScreens = fresh == 0 ? barrenScreens + 1 : 0;
            if (barrenScreens >= BARREN_SCREENS_BEFORE_STOP) {
                logInfo("ChatCaptureRoutine | " + channel + ": reached already-captured history.");
                break;
            }

            swipeUpThroughHistory();
        }

        return stored;
    }

    /** Reads one region of the held frame, returning empty rather than throwing on a bad read. */
    private String readRegion(RawImageData frame, PointData topLeft, PointData bottomRight) {
        try {
            String text = OcrEngine.recognizeText(frame, topLeft, bottomRight, CHAT_TEXT_SETTINGS);
            return text == null ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Keeps a frame whose rows could not be stored.
     *
     * <p>Successful frames are deleted as soon as their rows are safe. A failure is the one case
     * where the image is still the only evidence, and the standing rule is to dump the frame rather
     * than guess at what went wrong.
     */
    private void keepUnstoredFrame(String channel, int scrollIndex, BufferedImage image) {
        try {
            Path dir = baseDir().resolve("failed");
            Files.createDirectories(dir);
            Path out = dir.resolve(channel + "-" + LocalDateTime.now().format(FILE_STAMP)
                    + "-" + scrollIndex + ".png");
            ImageIO.write(image, "png", out.toFile());
            logWarning("ChatCaptureRoutine | Kept the unstored frame at " + out);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not keep the failed frame: " + e.getMessage());
        }
    }

    private void swipeUpThroughHistory() {
        // A downward drag reveals content above the current view, i.e. older messages -- the
        // opposite of how a page-down gesture reads.
        swipe(new PointData(FEED_X, FEED_TOP + 120), new PointData(FEED_X, FEED_BOTTOM - 120));
        sleepTask(700L);
    }

    private Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }
}
