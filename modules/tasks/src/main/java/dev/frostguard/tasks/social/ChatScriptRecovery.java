package dev.frostguard.tasks.social;

import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Gives a second reading, in another script, to the lines a Latin reader could make no word out of.
 *
 * <p>Reading the whole feed with the Chinese, Japanese and Korean packs alongside the Latin ones
 * measured clearly worse, not better: Tesseract hedges across the alphabets and mangles Latin it had
 * been reading correctly, and sender lines found on a fixed set of frames fell from 85 to 59 while
 * the pass took two and a half times as long. So the Latin languages stay the default and the other
 * scripts are spent only where the first pass came back with nothing word-shaped -- a Chinese
 * message read by a Latin-only reader comes back as a handful of stray punctuation, which is cheap
 * to recognise and cheap to read again.
 *
 * <p>Lives outside {@link ChatCaptureRoutine} so a bench can run it over saved frames. As a private
 * method on the routine it could only be exercised by driving the whole task against a live
 * emulator, which is why its first version shipped unmeasured.
 *
 * <p><b>Known limit.</b> Measured against a live Chinese message ("恭喜～", read by a human off the
 * frame), this recovers nothing, and the reason is the model rather than anything here. The
 * bundled chi_sim pack loads and works -- it reads timestamps out of the same feed -- but on the
 * message itself it returns nothing above the reader's confidence floor, and on a clean synthetic
 * rendering of the same three characters at 48px it returns "东 ANY 山". Neither the page
 * segmentation mode (all five measured), nor the bubble's polarity (that message is grey on a white
 * bubble, a contrast ratio of 1.68 against 3-plus everywhere else), nor contrast normalisation
 * moves it. The pack in tools/tesseract is the "fast" variant; reading CJK properly means the
 * "best" variant, which is an order of magnitude larger, and that is a file-size decision rather
 * than a code one. This class is left wired because it costs nothing on a Latin feed and starts
 * working the day a better model is dropped in.
 */
final class ChatScriptRecovery {

    /** A run this long of letters is a word; anything shorter is what noise looks like. */
    private static final int LETTERS_THAT_MAKE_A_WORD = 3;

    private ChatScriptRecovery() {
    }

    /**
     * @param frame     the screen the lines were read from
     * @param lines     the Latin pass's result
     * @param rightEdge the right edge of the text column, so a re-read cannot wander into the
     *                  bubble furniture
     * @param cjk       the non-Latin reader settings
     */
    static List<TextLine> reread(RawImageData frame, List<TextLine> lines, int rightEdge,
                                 OcrSettingsData cjk) {
        List<TextLine> out = new ArrayList<>(lines.size());
        for (TextLine line : lines) {
            if (hasReadableWord(line.text())) {
                out.add(line);
                continue;
            }
            TextLine recovered = readAgain(frame, line, rightEdge, cjk);
            out.add(recovered != null ? recovered : line);
        }
        return out;
    }

    /** How many lines {@link #reread} would replace, for logging. */
    static int recoveredCount(List<TextLine> before, List<TextLine> after) {
        int changed = 0;
        for (int i = 0; i < before.size() && i < after.size(); i++) {
            if (!before.get(i).text().equals(after.get(i).text())) {
                changed++;
            }
        }
        return changed;
    }

    private static TextLine readAgain(RawImageData frame, TextLine line, int rightEdge,
                                      OcrSettingsData cjk) {
        try {
            List<TextLine> again = OcrEngine.recognizeLines(frame,
                    new PointData(Math.max(0, line.left() - 4), Math.max(0, line.top() - 4)),
                    new PointData(Math.min(rightEdge, line.right() + 4), line.bottom() + 4),
                    cjk);
            TextLine best = null;
            for (TextLine candidate : again) {
                if (!candidate.text().isBlank()
                        && (best == null || candidate.text().length() > best.text().length())) {
                    best = candidate;
                }
            }
            // Accepted only when the second reading actually found another script. Without this the
            // CJK pass is free to replace a line of noise with a different line of noise, which
            // reads as progress and is not.
            if (best != null && countScriptCharacters(best.text()) > 0) {
                return new TextLine(best.text(), line.left(), line.top(),
                        line.width(), line.height(), best.confidence());
            }
        } catch (OcrException | RuntimeException e) {
            // A failed second look is not worth abandoning the screen for.
        }
        return null;
    }

    /** Whether a line holds anything a Latin reader would call a word. */
    static boolean hasReadableWord(String text) {
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                if (++run >= LETTERS_THAT_MAKE_A_WORD) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    /** Characters outside the Latin alphabet, which is what the second reading is looking for. */
    static int countScriptCharacters(String text) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c > 127
                    && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) {
                found++;
            }
        }
        return found;
    }
}
