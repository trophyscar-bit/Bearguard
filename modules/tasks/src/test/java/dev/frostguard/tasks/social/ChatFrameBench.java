package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Runs saved chat frames through the live reader and prints what it would have stored.
 *
 * <p>Off-device so a change can be measured against the same pixels every time, instead of against
 * whatever happened to be on screen that pass. Not a test -- it asserts nothing; it is the bench the
 * accuracy numbers come from. Point it at a directory of 720x1280 PNGs.
 */
public final class ChatFrameBench {

    private ChatFrameBench() {
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        File[] frames = new File(dir).listFiles(f -> f.getName().matches("f[0-9]+[.]png"));
        if (frames == null || frames.length == 0) {
            System.out.println("no frames in " + dir);
            return;
        }
        java.util.Arrays.sort(frames, java.util.Comparator.comparing(File::getName));

        OcrSettingsData cjk = OcrSettingsData.assembler()
                .textLayout(TextLayout.TEXT_BLOCK)
                .stripBackground(false)
                .language("chi_sim+jpn+kor")
                .build();

        OcrSettingsData cyrillic = OcrSettingsData.assembler()
                .textLayout(TextLayout.TEXT_BLOCK)
                .stripBackground(false)
                .language("rus")
                .build();

        OcrSettingsData latin = OcrSettingsData.assembler()
                .textLayout(TextLayout.TEXT_BLOCK)
                .stripBackground(false)
                .language("eng+spa+por+tur")
                .build();

        // Same holding map the routine uses, so overlapping screens collapse here exactly as they
        // do live. Without it the bench counts one message once per screen it appears on and every
        // number it reports is inflated.
        // The real translator, not a stub. Measuring with translation stubbed out was how a
        // cross-section went to matt showing every Spanish message stored untranslated, when what
        // was actually wrong was narrower and different.
        dev.frostguard.engine.chat.ChatTranslator translator =
                new dev.frostguard.engine.chat.ChatTranslator(true, 512);
        java.util.function.Function<String, Optional<String>> translate = translator::toEnglish;

        java.util.LinkedHashMap<String, ChatMessage> collected = new java.util.LinkedHashMap<>();
        java.util.Set<String> roster = new java.util.LinkedHashSet<>();
        for (File f : frames) {
            BufferedImage img = ImageIO.read(f);
            RawImageData raw = toRaw(img);
            List<TextLine> lines = OcrEngine.recognizeLines(raw,
                    new PointData(132, 250), new PointData(700, 1160), latin);
            List<TextLine> words = OcrEngine.recognizeWords(raw,
                    new PointData(132, 250), new PointData(700, 1160), latin);
            if (System.getenv("DUMP_WORDS") != null) {
                java.io.PrintStream w = new java.io.PrintStream(System.out, true, "UTF-8");
                w.println("=== " + f.getName() + " ===");
                for (TextLine l : lines) {
                    w.printf("LINE y=%4d..%4d x=%3d..%3d | %s%n",
                            l.top(), l.bottom(), l.left(), l.right(), l.text());
                    StringBuilder sb = new StringBuilder("      words: ");
                    for (TextLine ww : words) {
                        int top = Math.max(l.top(), ww.top());
                        int bot = Math.min(l.bottom(), ww.bottom());
                        if (bot - top > 0 && bot - top >= 0.5 * Math.min(l.height(), ww.height())) {
                            sb.append('[').append(ww.text()).append(' ')
                              .append(ww.left()).append("..").append(ww.right()).append("] ");
                        }
                    }
                    w.println(sb);
                }
            }
            lines = ChatScriptRecovery.reread(raw, lines, words, 700, cjk, cyrillic);
            lines = ChatOrnamentFilter.clean(lines, words, img);
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
            List<ChatMessage> msgs = ChatFrameReader.read(lines, "alliance", Instant.now(),
                    translate);
            for (ChatMessage m : msgs) {
                ChatCaptureRoutine.keep(collected, m);
            }
        }

        for (ChatMessage m : collected.values()) {
            if (!m.author().isBlank()) {
                roster.add(m.author());
            }
            roster.addAll(m.mentions());
        }

        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        int n = 0;
        for (ChatMessage held : collected.values()) {
            String fixed = ChatLineCleaner.repairLeadingMention(held.body(), roster);
            ChatMessage m = held.withBody(fixed);
            if (!held.translated().isBlank()) {
                m = m.withTranslated(
                        ChatLineCleaner.repairLeadingMention(held.translated(), roster));
            }
            n++;
            out.printf("%3d | %-22s | %-8s | %s%n", n,
                    m.allianceTag().isEmpty() ? m.author() : "[" + m.allianceTag() + "]" + m.author(),
                    m.kind(), m.body());
            if (!m.translated().isBlank()) {
                out.printf("    | EN: %s%n", m.translated());
            }
            if (!m.mentions().isEmpty()) {
                out.println("    | mentions: " + m.mentions());
            }
        }
        out.println("total messages: " + n);
    }

    /** Packs an image the way the emulator hands one over, so the bench walks the real code path. */
    public static RawImageData toRaw(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] px = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                px[i++] = (byte) ((rgb >> 16) & 0xFF);
                px[i++] = (byte) ((rgb >> 8) & 0xFF);
                px[i++] = (byte) (rgb & 0xFF);
                px[i++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(px, w, h, 32);
    }
}
