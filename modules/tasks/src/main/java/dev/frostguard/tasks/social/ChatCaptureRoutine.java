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
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.SearchConfigConstants;
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
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Captures World, Alliance and Personal chat on a schedule and stores it as a readable transcript.
 *
 * <p><b>The frame is read whole, and position tells the rest.</b> Reading region by region meant
 * choosing the boundary of every sender line and every bubble before recognising them, and each of
 * those boundaries was an offset from an avatar edge that moves with crowns and rank badges. A few
 * pixels of drift clipped the tops of glyphs, so a line that plainly reads {@code [INF]Mini TyTy}
 * came back as {@code UNF jMini TyTy}; when a band landed high the sender line fell into the bubble
 * instead and the message was stored with no author. Recognised whole, the same frame reports every
 * line with where it sat, and the two kinds of line separate themselves by the column they start
 * in. Measured over 20 live frames that removed every invented name -- seven distinct senders, all
 * real players, against fourteen of which half were wreckage -- and it costs one recognition per
 * screen rather than a dozen.
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

    /** The feed as a rectangle, for reading the whole thing in one recognition. */
    /**
     * The strip of the feed that actually holds text.
     *
     * <p>It starts at the text column, not at the edge of the screen. Reading from x=0 put the
     * avatar, its rank badge and the bubble's own left border inside the recognition, and the
     * reader turned them into letters: "En 1:45 hora batalla" came back as "İd EntA4S hora
     * batalla", with the bubble edge fused onto the first word, and a bare "y" appeared as a
     * message of its own where a piece of bubble art sat alone on a line. None of that is text and
     * none of it needed reading.
     *
     * <p>Sender lines begin around x=139, so the column starts a little inside that and still
     * catches them. The right edge stops before the per-message translate control.
     */
    private static final int TEXT_COLUMN_LEFT = 132;
    private static final int TEXT_COLUMN_RIGHT = 700;

    private static final PointData FEED_TOP_LEFT = new PointData(TEXT_COLUMN_LEFT, FEED_TOP);
    private static final PointData FEED_BOTTOM_RIGHT = new PointData(TEXT_COLUMN_RIGHT, FEED_BOTTOM);

    /**
     * Reader configuration for a chat region.
     *
     * <p>English-only is deliberate and evidence-backed: adding chi_sim was net-negative live,
     * because CJK's much larger glyph set false-matches small UI icons and badges as characters,
     * putting stray glyphs into otherwise-Latin text. Foreign chat is handled downstream by
     * translating the read text rather than by widening the reader.
     */
    /**
     * A second reading, in the scripts the first one cannot see.
     *
     * <p>Loading every script at once measured worse, not better: with ten languages the reader
     * hedges across alphabets and mangles Latin it had been reading correctly, and sender lines
     * found on a fixed set of frames fell from 85 to 59 while the pass took two and a half times
     * as long. So the Latin languages stay the default and this is used only on the lines the
     * first pass could not make a word out of -- a Chinese message comes back from a Latin-only
     * reader as a handful of stray punctuation, which is easy to recognise and cheap to re-read.
     */
    private static final OcrSettingsData CHAT_CJK_SETTINGS =
            OcrSettingsData.assembler()
                    // A block, not a line, though it is handed one row. Measured both ways on a
                    // live Chinese bubble through the Tesseract CLI: as a block it reads, as a
                    // single line it returns an empty page. The reasoning that it "is" one line is
                    // sound and the reader disagrees, so the reader wins.
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    .language("chi_sim+jpn+kor")
                    .build();

    private static final OcrSettingsData CHAT_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    // Measured over 20 live alliance frames: reading with Spanish, Portuguese
                    // and Turkish alongside English lifted the text recognised by 19% and the
                    // sender lines found by 29% (66 to 85), and made a name error disappear that
                    // no amount of cleanup had shifted -- "JAthenaRyu" was English being forced
                    // onto Spanish text. Adding further languages measured WORSE, not better:
                    // ten of them dropped senders to 59 and cost 2.6x the time, because Tesseract
                    // hedges across alphabets and mangles Latin it was reading correctly. More
                    // scripts belong behind per-message script detection, not in this list.
                    .language("eng+spa+por+tur")
                    .preserveLineBreaks(true)
                    .build();

    /** Taps at the close control before falling back to Back. */
    private static final int CHAT_CLOSE_ATTEMPTS = 3;

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
        try {
            if (includeWorld) {
                totalNew += captureChannel("world", TAB_WORLD);
            }
            if (includeAlliance) {
                totalNew += captureChannel("alliance", TAB_ALLIANCE);
            }
            if (includePersonal) {
                totalNew += captureChannel("personal", TAB_PERSONAL);
            }
        } catch (RuntimeException failure) {
            // The queue retries a throwing task immediately and does not print the cause, so a
            // crash-loop shows up as the task restarting every few seconds with nothing to explain
            // it. Naming the failure here is the difference between a mystery and a stack trace.
            logError("ChatCaptureRoutine | Pass failed: " + failure, failure);
            throw failure;
        } finally {
            // Closing chat has to happen whether the pass succeeded, threw, or gave up. It used to
            // sit on the success path only, so a failure left the game parked on the chat window --
            // and the next task inherited it. Observed live: this routine crash-looped, and the
            // Intelligence routine that followed spent every attempt hunting a city building while
            // a chat panel filled the screen, reporting "Intel unreachable" for hours. A task that
            // cannot finish its own work still owes the next one a screen it recognises.
            closeChat();
        }

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

        // Keyed on the message body so the same line seen on overlapping screens is held once.
        java.util.LinkedHashMap<String, ChatMessage> collected = new java.util.LinkedHashMap<>();
        java.util.Set<String> roster = new java.util.LinkedHashSet<>();
        int knownBefore = 0;
        int barrenScreens = 0;

        for (int i = 0; i < scrollBack; i++) {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                logWarning("ChatCaptureRoutine | Could not capture a frame for " + channel
                        + "; stopping this channel.");
                break;
            }

            BufferedImage image = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);

            // One recognition for the whole feed, keeping where every line landed. Reading region
            // by region meant deciding each boundary before recognising it, and those boundaries
            // were offsets from an avatar edge that moves with crowns and rank badges -- a few
            // pixels of drift clipped glyph tops, or dropped the sender line into the bubble.
            List<TextLine> lines;
            try {
                lines = OcrEngine.recognizeLines(frame, FEED_TOP_LEFT, FEED_BOTTOM_RIGHT, CHAT_TEXT_SETTINGS);
                List<TextLine> latin = lines;
                // Same reading again, one word at a time, so the bubble's furniture can be told
                // from the sentence by where it sits. Cheap next to the recognition itself, which
                // has already done the work; this only asks for it reported finer.
                List<TextLine> words = OcrEngine.recognizeWords(
                        frame, FEED_TOP_LEFT, FEED_BOTTOM_RIGHT, CHAT_TEXT_SETTINGS);
                // The other-script re-read goes FIRST. It keys on a line the Latin reader could
                // make no word of, which is exactly what the ornament filter is also looking at:
                // run the other way round, a Chinese message read as "g2 - E o" had its pieces
                // thrown out as furniture -- their glyph widths are nothing like a Latin row's --
                // and the line was gone before anything tried to read it in another script.
                lines = ChatScriptRecovery.reread(frame, lines, words, TEXT_COLUMN_RIGHT, CHAT_CJK_SETTINGS);
                lines = ChatOrnamentFilter.clean(lines, words, image);
                int recovered = ChatScriptRecovery.recoveredCount(latin, lines);
                if (recovered > 0) {
                    logInfo("ChatCaptureRoutine | Re-read " + recovered + " line(s) in another script.");
                }
            } catch (OcrException e) {
                logWarning("ChatCaptureRoutine | Could not read " + channel + " screen "
                        + (i + 1) + ": " + e.getMessage());
                swipeUpThroughHistory();
                continue;
            }
            if (lines.isEmpty()) {
                logInfo("ChatCaptureRoutine | " + channel + ": no text on this screen.");
                swipeUpThroughHistory();
                continue;
            }

            // Every sender line names a member, whether or not their message survived reading.
            // Collected here rather than from the stored messages because the two are not the same
            // set: "Maki" was on screen as a sender the same afternoon a message addressed to
            // @Maki went unrepaired, because Maki's own message had not been read that pass.
            for (TextLine l : lines) {
                ChatLineCleaner.Sender sender = ChatLineCleaner.parseSender(l.text());
                // The alliance tag is what makes it a sender line rather than a message that
                // happens to parse like one. Without it "y congrats" joined the roster as a member
                // called "congrats", and the next message beginning with that word was rewritten
                // into a mention of them.
                if (sender.trusted() && !sender.allianceTag().isEmpty() && !sender.name().isBlank()) {
                    roster.add(sender.name());
                }
            }

            List<ChatMessage> messages = ChatFrameReader.read(
                    lines, channel, Instant.now(), body -> translator.toEnglish(body));

            // Hold the pass in memory rather than writing each screen as it is read. Scroll steps
            // overlap by design, so most messages are seen on two or three screens -- and a message
            // sitting at the top of one screen has its sender line above the frame edge, while the
            // next screen back shows both. Writing immediately stored whichever copy arrived first,
            // which for those messages is the one with no author on it. Keeping the pass together
            // lets the attributed copy win.
            for (ChatMessage m : messages) {
                keep(collected, m);
            }

            // Per-screen accounting. Without it a pass is a black box: the only way to tell a
            // successful walk from one that read nothing was the total at the end, which hides
            // where in the scroll-back it stopped finding anything.
            int freshOnScreen = collected.size() - knownBefore;
            logInfo("ChatCaptureRoutine | " + channel + " screen " + (i + 1) + "/" + scrollBack
                    + ": " + lines.size() + " line(s), " + messages.size() + " readable, "
                    + freshOnScreen + " new.");
            knownBefore = collected.size();

            // Overlapping scroll-backs mean most screens repeat the one before. Two consecutive
            // screens contributing nothing means this pass has reached history it has already
            // walked, and going further only spends captures re-reading it.
            barrenScreens = freshOnScreen == 0 ? barrenScreens + 1 : 0;
            if (barrenScreens >= BARREN_SCREENS_BEFORE_STOP) {
                logInfo("ChatCaptureRoutine | " + channel + ": reached already-captured history.");
                break;
            }

            swipeUpThroughHistory();
        }

        // The pass is written once, in the order it was read, now that every message has had the
        // chance to be seen on a screen that also showed its sender.
        try {
            return store.append(repairMentions(new java.util.ArrayList<>(collected.values()), roster));
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not write the transcript for " + channel
                    + ": " + e.getMessage());
            return 0;
        }
    }

    /** Reads one region of the held frame, returning empty rather than throwing on a bad read. */

    /** Three letters in a row is a word; anything less is the reader guessing at shapes. */
    private static final int LETTERS_THAT_MAKE_A_WORD = 3;

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

    /**
     * Returns the game to a screen the next task will recognise.
     *
     * <p>Verified rather than assumed: a single blind tap on the close control is exactly the kind
     * of "it probably worked" step that strands the game somewhere unexpected. The chat panel can
     * also be more than one layer deep, so this taps and checks until a home anchor is visible.
     */
    private void closeChat() {
        for (int attempt = 1; attempt <= CHAT_CLOSE_ATTEMPTS; attempt++) {
            if (isOnAHomeScreen()) {
                return;
            }
            tapNear(CHAT_CLOSE);
            sleepTask(700L);
        }

        if (isOnAHomeScreen()) {
            return;
        }
        // Say so plainly rather than leaving the next task to discover it. pressBack carries the
        // quit-dialog guard, so it is the safer last resort than tapping more coordinates.
        logWarning("ChatCaptureRoutine | Chat did not close after " + CHAT_CLOSE_ATTEMPTS
                + " attempts; falling back to Back so the next task does not inherit this screen.");
        pressBack();
        sleepTask(700L);
        if (!isOnAHomeScreen()) {
            logWarning("ChatCaptureRoutine | Still not on a recognised screen after closing chat.");
        }
    }

    /** Either home screen counts: the next task navigates from whichever one it needs. */
    private boolean isOnAHomeScreen() {
        return templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                        SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.QUICK_SEARCH).isFound();
    }

    /**
     * How far one scroll step travels, as a fraction of the drag the feed height allows.
     *
     * <p>A full-height drag moves the feed by very nearly one screen, which sounds efficient and is
     * actually the bug: measured across consecutive captures, neighbouring frames shared only one
     * or two lines out of ten to fifteen. That is no margin at all. It holds while every message is
     * a line or two, and the moment somebody posts a wall of text the drag steps straight over it
     * and the message is gone with nothing to show it ever existed.
     *
     * <p>A little over half a screen buys back the overlap. The cost is duplicates, which cost
     * nothing: de-duplication keys on the message body, so a line seen twice is stored once.
     */
    private static final double SCROLL_STEP_FRACTION = 0.53;

    /**
     * How long the scroll drag takes.
     *
     * <p>This is the difference between a drag and a flick. A swipe sent with no duration is a
     * bare {@code input swipe}, which Android reads as a flick and answers with momentum
     * scrolling -- the feed keeps travelling after the finger lifts, by an amount that depends on
     * how quickly the emulator serviced the gesture rather than on anything asked for. That is
     * what an occasional step "going extra" looks like from the outside: most scrolls land where
     * they should and every so often one sails past a message, and no reduction in distance fixes
     * it because the distance was never the problem.
     *
     * <p>The same fault was found on the world map, where a pan meant to move one screen ended up
     * in unrelated rival cities, which is why the duration-aware overload exists at all. Chat had
     * simply never used it.
     */
    private static final int SCROLL_DRAG_MS = 700;

    private void swipeUpThroughHistory() {
        // A downward drag reveals content above the current view, i.e. older messages -- the
        // opposite of how a page-down gesture reads.
        int from = FEED_TOP + 120;
        int travel = (int) Math.round(((FEED_BOTTOM - 120) - from) * SCROLL_STEP_FRACTION);
        swipe(new PointData(FEED_X, from), new PointData(FEED_X, from + travel), SCROLL_DRAG_MS);
        sleepTask(700L);
    }

    private Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }

    /**
     * Puts back the "@" the reader lost, now that the whole pass has been walked.
     *
     * <p>Deliberately last. The names it matches against come from the sender lines of this same
     * pass, so it needs every screen read before it knows who is in the alliance -- a mention of
     * somebody whose own messages are further back in the history is only repairable once that far
     * back has been reached.
     */
    private static java.util.List<ChatMessage> repairMentions(java.util.List<ChatMessage> messages,
                                                              java.util.Set<String> roster) {
        for (ChatMessage m : messages) {
            if (!m.author().isBlank()) {
                roster.add(m.author());
            }
            roster.addAll(m.mentions());
        }
        java.util.List<ChatMessage> out = new java.util.ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            String repaired = ChatLineCleaner.repairLeadingMention(m.body(), roster);
            if (repaired.equals(m.body())) {
                out.add(m);
                continue;
            }
            // The English was made from the body as it read before the repair, so it carries the
            // same wreckage: "@Maki felicidades!" was stored correctly while its translation still
            // said "yy Maki congratulations!". Repaired the same way rather than translated again,
            // because the mangled "@" survives translation unchanged and a second lookup would buy
            // nothing but a request.
            String english = m.translated() == null || m.translated().isBlank()
                    ? m.translated()
                    : ChatLineCleaner.repairLeadingMention(m.translated(), roster);
            out.add(m.withBody(repaired).withTranslated(english));
        }
        return out;
    }

    /**
     * Files one message against what the pass has already read, treating a re-read as the same
     * message rather than a new one.
     *
     * <p>The exact key is tried first because it is a map lookup; the scan behind it only runs when
     * that misses, and only that message's own re-reads can match it. Where two copies disagree the
     * fuller one wins -- a message clipped by the bottom of the screen is still a correct reading of
     * its first half, so length is the honest tie-break -- except that an attributed copy always
     * beats an unattributed one, because a message read at the top of a screen has its sender line
     * above the frame edge and comes back with no author at all.
     */
    static void keep(java.util.LinkedHashMap<String, ChatMessage> collected, ChatMessage m) {
        String key = ChatLineCleaner.mergeKey(m.body());
        String match = collected.containsKey(key) ? key : null;
        if (match == null) {
            for (String seen : collected.keySet()) {
                if (ChatLineCleaner.sameMessage(seen, key)) {
                    match = seen;
                    break;
                }
            }
        }
        if (match == null) {
            collected.put(key, m);
            return;
        }
        ChatMessage held = collected.get(match);
        ChatMessage winner = better(m, held) ? m : held;
        ChatMessage other = winner == m ? held : m;
        // A message read on two screens is not read the same way twice, and the copy with the
        // fuller body is not always the copy that kept its mention: the long alliance call was
        // clipped on the screen where "@All" was legible and complete on the screen where it was
        // not, so whichever copy won, something was lost. The body comes from the fuller reading
        // and the mention from whichever reading kept one.
        if (!winner.body().startsWith("@") && other.body().startsWith("@")) {
            int end = other.body().indexOf(' ');
            String mention = end < 0 ? other.body() : other.body().substring(0, end);
            winner = winner.withBody(mention + " " + winner.body());
        }
        if (winner != held) {
            collected.remove(match);
            collected.put(key, winner);
        } else if (winner != collected.get(match)) {
            collected.put(match, winner);
        }
    }

    static boolean better(ChatMessage candidate, ChatMessage held) {
        if (held.author().isEmpty() != candidate.author().isEmpty()) {
            return held.author().isEmpty();
        }
        return candidate.body().length() > held.body().length();
    }

}
