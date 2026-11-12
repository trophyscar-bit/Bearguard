package dev.frostguard.tasks.combat;

import dev.frostguard.vision.ocr.TextLine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the game's bear really ends. A bot restarted mid-bear used to assume "now + 30 minutes" and
 * kept joining rallies for up to half an hour after the game's bear had gone (Nov 12: the restart at
 * 14:03 put the end at 14:33, three minutes after the real 14:30 end).
 */
final class BearTrapEnd {

    private static final Pattern ENDS_IN = Pattern.compile("ends\\s*in\\W{0,3}\\s*(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*[:.]\\s*(\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private BearTrapEnd() {
    }

    /** The "Ends in: 00:29:48" countdown the world map shows over the bear, if a line carries all of it. */
    static Optional<Duration> endsIn(List<TextLine> lines) {
        for (TextLine line : lines) {
            Matcher m = ENDS_IN.matcher(line.text());
            if (m.find()) {
                long h = Long.parseLong(m.group(1));
                long min = Long.parseLong(m.group(2));
                long sec = Long.parseLong(m.group(3));
                if (min < 60 && sec < 60 && h <= 1) return Optional.of(Duration.ofSeconds(h * 3600 + min * 60 + sec));
            }
        }
        return Optional.empty();
    }

    /**
     * The end of a bear the bot joined part-way through (a restart, or a manual start): the scheduled
     * activation plus the trap length when now falls inside that window, otherwise now plus the length.
     */
    static LocalDateTime restartedEnd(LocalDateTime now, LocalDateTime scheduledActivation, int trapMinutes) {
        if (scheduledActivation != null && !now.isBefore(scheduledActivation)
                && now.isBefore(scheduledActivation.plusMinutes(trapMinutes))) {
            return scheduledActivation.plusMinutes(trapMinutes);
        }
        return now.plusMinutes(trapMinutes);
    }

    /** The earlier of the planned end and the end the game showed, so the bot never outstays the bear. */
    static LocalDateTime earliest(LocalDateTime planned, LocalDateTime shownByGame) {
        return shownByGame != null && shownByGame.isBefore(planned) ? shownByGame : planned;
    }
}
