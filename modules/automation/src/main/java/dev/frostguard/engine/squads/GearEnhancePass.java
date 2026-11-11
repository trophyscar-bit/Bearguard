package dev.frostguard.engine.squads;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.squads.EnhanceDialogReader.Badge;
import dev.frostguard.engine.squads.EnhanceDialogReader.Dialog;
import dev.frostguard.engine.squads.ForgeDialogReader.GearDetails;
import dev.frostguard.engine.squads.GearEnhancePlanner.Raise;

/**
 * Raises one piece a level at a time, starting and ending on its Gear Details. Quick Select picks the
 * fodder, so the whole list is scrolled and any selected tile that isn't an XP item or grey or green
 * gear abandons the level. Only the Enhance button of a confirmed dialog spends anything, and only when
 * live. Any screen that doesn't read as expected stops the run.
 */
public final class GearEnhancePass {

    private static final Map<String, String> GEAR_SET = Map.of(
            "Mia", "Saboteur", "Molly", "Saboteur", "Flint", "Guardian", "Sergey", "Guardian", "Bahiti", "Sniper");
    private static final int READ_ATTEMPTS = 4;
    private static final int READ_PAUSE_MS = 700;
    private static final int PRESS_READ_ATTEMPTS = 6;
    private static final int MAX_SWIPES = 40;
    private static final int SWIPE_MS = 900;
    private static final int BUTTON_RADIUS = 8;

    public enum Stop {
        TARGET_REACHED, DRY_RUN, DEADLINE, WRONG_BUTTON, WRONG_PIECE, WRONG_LEVEL, DIALOG_UNREADABLE,
        QUICK_SELECT_SHORT, PREVIEW_MISMATCH, UNSAFE_FODDER, LIST_SCAN_FAILED, SELECTION_CHANGED, PRESS_UNCONFIRMED,
        MARKER_UNSAVED
    }

    /** {@code level} is the last level confirmed on screen and {@code raised} how many this run added. */
    public record Outcome(int level, int raised, Stop stop, String detail) {
    }

    /** The emulator, as far as this pass is allowed to touch it. */
    public interface Device {
        RawImageData capture();

        void tap(PointData point, int radius);

        void swipe(PointData from, PointData to, int durationMs);

        void back();

        void pause(int millis);

        boolean pastDeadline();

        /** Saves that this press is about to happen; a failure means it doesn't. */
        void pressing(String hero, String slot, int level) throws IOException;

        /** The press last saved by {@link #pressing} is confirmed on screen. */
        void pressConfirmed() throws IOException;

        /** The frame a read gave up on, just before the halt it causes. Must not touch the screen. */
        default void readFailed(RawImageData frame, String failure) {
        }
    }

    interface Reads {
        String detailsLabel(RawImageData frame);

        GearDetails details(RawImageData frame);

        Dialog dialog(RawImageData frame);

        List<Badge> badges(RawImageData frame);

        boolean listTop(RawImageData frame);

        boolean listBottom(RawImageData frame);

        Integer scrollOffset(RawImageData before, RawImageData after);
    }

    private static final Reads SCREEN = new Reads() {
        @Override
        public String detailsLabel(RawImageData frame) {
            return EnhanceDialogReader.detailsButtonLabel(frame);
        }

        @Override
        public GearDetails details(RawImageData frame) {
            return ForgeDialogReader.readGearDetails(frame);
        }

        @Override
        public Dialog dialog(RawImageData frame) {
            return EnhanceDialogReader.read(frame);
        }

        @Override
        public List<Badge> badges(RawImageData frame) {
            return EnhanceDialogReader.badges(frame);
        }

        @Override
        public boolean listTop(RawImageData frame) {
            return EnhanceDialogReader.isListTop(frame);
        }

        @Override
        public boolean listBottom(RawImageData frame) {
            return EnhanceDialogReader.isListBottom(frame);
        }

        @Override
        public Integer scrollOffset(RawImageData before, RawImageData after) {
            return EnhanceDialogReader.scrollOffset(before, after);
        }
    };

    private static final class Halt extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Stop stop;

        Halt(Stop stop, String detail) {
            super(detail);
            this.stop = stop;
        }
    }

    private final Device device;
    private final Reads reads;

    public GearEnhancePass(Device device) {
        this(device, SCREEN);
    }

    GearEnhancePass(Device device, Reads reads) {
        this.device = device;
        this.reads = reads;
    }

    /** Runs from {@code raise.from()} up to {@code raise.to()}; with {@code live} off it presses nothing that spends. */
    public Outcome run(Raise raise, boolean live) {
        int level = raise.from();
        int raised = 0;
        try {
            while (level < raise.to()) {
                if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached before Lv." + level);
                raiseOnce(raise, level, live);
                level++;
                raised++;
            }
            return new Outcome(level, raised, Stop.TARGET_REACHED, "reached Lv." + level);
        } catch (Halt halt) {
            return new Outcome(level, raised, halt.stop, halt.getMessage());
        }
    }

    private void raiseOnce(Raise raise, int level, boolean live) {
        openDialog(raise, level);
        try {
            Dialog selected = quickSelect(level);
            scanFodder();
            Dialog now = await(reads::dialog, "the dialog did not read after the list scan");
            if (!selected.equals(now)) {
                throw new Halt(Stop.SELECTION_CHANGED, "selection moved during the list scan: " + selected + " then " + now);
            }
            if (!live) throw new Halt(Stop.DRY_RUN, "would raise " + raise.hero() + " " + raise.slot() + " to Lv." + (level + 1));
            if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached before pressing Enhance");
        } catch (Halt halt) {
            close();
            throw halt;
        }
        press(raise, level);
    }

    private void openDialog(Raise raise, int level) {
        String label = awaitLabel();
        if (!EnhanceDialogReader.ENHANCE_LABEL.equals(label)) {
            throw new Halt(Stop.WRONG_BUTTON, "the Gear Details button reads " + label);
        }
        GearDetails details = await(reads::details, "Gear Details did not read");
        String expected = GEAR_SET.get(raise.hero()) + " " + raise.slot();
        if (!expected.equals(details.piece())) {
            throw new Halt(Stop.WRONG_PIECE, "expected " + expected + ", screen shows " + details.piece());
        }
        if (details.enhancement() != level) {
            throw new Halt(Stop.WRONG_LEVEL, "expected +" + level + ", Gear Details shows +" + details.enhancement());
        }
        if (!EnhanceDialogReader.ENHANCE_LABEL.equals(reads.detailsLabel(device.capture()))) {
            throw new Halt(Stop.WRONG_BUTTON, "the Gear Details button changed before the tap");
        }
        device.tap(EnhanceDialogReader.DETAILS_ENHANCE, BUTTON_RADIUS);
        device.pause(1600);
        Dialog dialog = await(reads::dialog, "the Gear Enhancement dialog did not read");
        try {
            if (dialog.level() != level || dialog.preview() != null) {
                throw new Halt(Stop.WRONG_LEVEL, "expected Lv." + level + " with nothing selected, read " + dialog);
            }
            if (!EnhanceDialogReader.QUICK_SELECT_LABEL.equals(dialog.leftButton())) {
                throw new Halt(Stop.DIALOG_UNREADABLE, "the left button reads " + dialog.leftButton());
            }
        } catch (Halt halt) {
            close();
            throw halt;
        }
    }

    private Dialog quickSelect(int level) {
        device.tap(EnhanceDialogReader.QUICK_SELECT, BUTTON_RADIUS);
        device.pause(1200);
        Dialog selected = await(reads::dialog, "the dialog did not read after Quick Select");
        for (int attempt = 0; attempt < READ_ATTEMPTS && selected.preview() == null; attempt++) {
            device.pause(READ_PAUSE_MS);
            selected = await(reads::dialog, "the dialog did not read after Quick Select");
        }
        if (selected.preview() == null) throw new Halt(Stop.QUICK_SELECT_SHORT, "Quick Select could not fill Lv." + level);
        if (selected.level() != level || selected.preview() != level + 1) {
            throw new Halt(Stop.PREVIEW_MISMATCH, "expected Lv." + level + " to Lv." + (level + 1) + ", read " + selected);
        }
        if (!selected.enhanceLit() || !EnhanceDialogReader.SELECT_MORE_LABEL.equals(selected.leftButton())) {
            throw new Halt(Stop.PREVIEW_MISMATCH, "the buttons did not change after Quick Select: " + selected);
        }
        return selected;
    }

    private void scanFodder() {
        RawImageData frame = device.capture();
        if (!reads.listTop(frame)) throw new Halt(Stop.LIST_SCAN_FAILED, "the fodder list did not open at its top");
        int seen = 0;
        for (int swipe = 0; swipe < MAX_SWIPES; swipe++) {
            seen += checkTiles(frame);
            if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached during the list scan");
            device.swipe(EnhanceDialogReader.SCROLL_FROM, EnhanceDialogReader.SCROLL_TO, SWIPE_MS);
            device.pause(900);
            RawImageData next = device.capture();
            Integer offset = reads.scrollOffset(frame, next);
            if (offset == null || offset > EnhanceDialogReader.MAX_SCROLL_STEP) {
                throw new Halt(Stop.LIST_SCAN_FAILED, "the list moved too far to prove every tile was seen: " + offset);
            }
            if (offset == 0) {
                if (!reads.listBottom(next)) throw new Halt(Stop.LIST_SCAN_FAILED, "the list stopped before its bottom");
                if (seen == 0) throw new Halt(Stop.LIST_SCAN_FAILED, "a level is selected but no minus badge was seen");
                return;
            }
            frame = next;
        }
        throw new Halt(Stop.LIST_SCAN_FAILED, "the list never reached its bottom");
    }

    private int checkTiles(RawImageData frame) {
        List<Badge> badges = reads.badges(frame);
        for (Badge badge : badges) {
            if (!badge.kind().safeFodder()) throw new Halt(Stop.UNSAFE_FODDER, "Quick Select chose " + badge);
        }
        return badges.size();
    }

    private void press(Raise raise, int level) {
        try {
            device.pressing(raise.hero(), raise.slot(), level);
        } catch (IOException e) {
            close();
            throw new Halt(Stop.MARKER_UNSAVED, "could not record the press, so it was not made: " + e.getMessage());
        }
        device.tap(EnhanceDialogReader.ENHANCE, BUTTON_RADIUS);
        device.pause(2500);
        for (int attempt = 0; attempt < PRESS_READ_ATTEMPTS; attempt++) {
            RawImageData frame = device.capture();
            Dialog after = reads.dialog(frame);
            if (after != null && after.preview() == null && after.level() == level + 1) {
                confirm(level);
                close();
                return;
            }
            if (after != null && after.level() > level + 1) {
                throw new Halt(Stop.PRESS_UNCONFIRMED, "expected Lv." + (level + 1) + " after the press, read " + after);
            }
            GearDetails details = after == null ? reads.details(frame) : null;
            if (details != null && details.enhancement() == level + 1) {
                confirm(level);
                return;
            }
            device.pause(READ_PAUSE_MS);
        }
        throw new Halt(Stop.PRESS_UNCONFIRMED, "Lv." + (level + 1) + " was not confirmed after the press");
    }

    private void confirm(int level) {
        try {
            device.pressConfirmed();
        } catch (IOException e) {
            throw new Halt(Stop.PRESS_UNCONFIRMED, "Lv." + (level + 1) + " showed but its record could not be cleared: " + e.getMessage());
        }
    }

    private void close() {
        device.back();
        device.pause(1500);
    }

    private String awaitLabel() {
        RawImageData frame = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            frame = device.capture();
            String label = reads.detailsLabel(frame);
            if (label != null && !label.isBlank()) return label;
            device.pause(READ_PAUSE_MS);
        }
        device.readFailed(frame, "Gear Details is not on screen");
        throw new Halt(Stop.DIALOG_UNREADABLE, "Gear Details is not on screen");
    }

    private <T> T await(Function<RawImageData, T> read, String failure) {
        RawImageData frame = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            frame = device.capture();
            T value = read.apply(frame);
            if (value != null) return value;
            device.pause(READ_PAUSE_MS);
        }
        device.readFailed(frame, failure);
        throw new Halt(Stop.DIALOG_UNREADABLE, failure);
    }
}
