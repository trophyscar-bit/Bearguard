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
 * Raises one piece from its open Gear Details to its target in a single visit to the Gear Enhancement
 * dialog: Quick Select fills the first level, Select more adds each next one while the dialog stays
 * open, then the fodder list is scrolled once and Enhance is pressed once. Any selected tile that isn't
 * an Enhancement Part abandons the whole selection: spare gear is never fed in. Only the Enhance button of a confirmed
 * dialog spends anything, and only when live. Any screen that doesn't read as expected stops the run.
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
    private static final int PARTS_READ_ATTEMPTS = 2;

    public enum Stop {
        TARGET_REACHED, DRY_RUN, DEADLINE, WRONG_BUTTON, WRONG_PIECE, WRONG_LEVEL, DIALOG_UNREADABLE,
        QUICK_SELECT_SHORT, PREVIEW_MISMATCH, UNSAFE_FODDER, LIST_SCAN_FAILED, SELECTION_CHANGED, PRESS_UNCONFIRMED,
        MARKER_UNSAVED,
        /** The parts on hand do not cover another level, so Quick Select reached for spare gear. A clean stop: nothing spent. */
        PARTS_SHORT
    }

    /**
     * What the enhance screen itself shows for the selection, saved to the log before the press so each raise
     * has its reason on one line: the stat it adds and the parts it eats. Null fields did not read.
     */
    public record Preview(String hero, String slot, int fromLevel, int toLevel, String stat, Double from, Double to, Integer parts) {
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

        /** The preview the selection shows, just before the list scan and the press. Must not touch the screen. */
        default void previewed(Preview preview) {
        }

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

        /** {selected, owned} of the Enhancement Parts tile, or null when it does not read. */
        int[] partsTile(RawImageData frame);

        /** The last stat row of the preview, or null when it does not read. */
        EnhanceDialogReader.Gain previewGain(RawImageData frame);

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
        public int[] partsTile(RawImageData frame) {
            return EnhanceDialogReader.partsTile(frame);
        }

        @Override
        public EnhanceDialogReader.Gain previewGain(RawImageData frame) {
            return EnhanceDialogReader.previewGain(frame);
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
        try {
            if (level >= raise.to()) return new Outcome(level, 0, Stop.TARGET_REACHED, "reached Lv." + level);
            if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached before Lv." + level);
            int reached = raiseTo(raise, level, live);
            if (reached >= raise.to()) return new Outcome(reached, reached - level, Stop.TARGET_REACHED, "reached Lv." + reached);
            return new Outcome(reached, reached - level, Stop.QUICK_SELECT_SHORT,
                    "stopped at Lv." + reached + ", short of Lv." + raise.to() + ": the XP items ran out or time did");
        } catch (Halt halt) {
            return new Outcome(level, 0, halt.stop, halt.getMessage());
        }
    }

    /** One visit to the dialog; returns the level confirmed on screen, which is short of the target when the fodder ran out. */
    private int raiseTo(Raise raise, int level, boolean live) {
        openDialog(raise, level);
        int reached;
        try {
            Dialog selected = quickSelect(level);
            selected = selectMore(selected, level, raise.to());
            reached = selected.preview();
            report(raise, level, reached);
            scanFodder();
            Dialog now = await(reads::dialog, "the dialog did not read after the list scan");
            if (!selected.equals(now)) {
                throw new Halt(Stop.SELECTION_CHANGED, "selection moved during the list scan: " + selected + " then " + now);
            }
            if (!live) throw new Halt(Stop.DRY_RUN, "would raise " + raise.hero() + " " + raise.slot() + " to Lv." + reached);
            if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached before pressing Enhance");
        } catch (Halt halt) {
            close();
            throw halt;
        }
        press(raise, level, reached);
        return reached;
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
        refuseSpareGearInView(level);
        return selected;
    }

    /**
     * With too few parts, Quick Select tops the level up with spare gear, and its minus badges show on the first
     * rows of the list. That is a shortage of parts, not a fault: stop at once, before any scan, and spend nothing.
     * Any other badge is a different matter and is left to the full scan to refuse.
     */
    private void refuseSpareGearInView(int level) {
        RawImageData frame = device.capture();
        List<Badge> badges = reads.badges(frame);
        boolean spareGear = badges.stream().anyMatch(b -> b.kind() == EnhanceDialogReader.TileKind.GREY
                || b.kind() == EnhanceDialogReader.TileKind.GREEN);
        if (!spareGear) return;
        int[] parts = reads.partsTile(frame);
        throw new Halt(Stop.PARTS_SHORT, (parts == null ? "the parts on hand" : "only " + parts[1] + " parts on hand")
                + " do not cover Lv." + level + " to Lv." + (level + 1) + ": Quick Select reached for spare gear");
    }

    /**
     * Presses Select more, staying in the dialog, until the preview reaches the target or stops rising
     * because the XP items ran out. A preview that falls, clears or goes past the target abandons the
     * selection: the target is the most the night may raise.
     */
    private Dialog selectMore(Dialog selected, int level, int target) {
        Dialog current = selected;
        int[] parts = readParts();
        // Each level costs about what the one before did, a little more. Another tap is made only while the
        // parts left cover that and one more, so a tap never has to be finished off with spare gear.
        int perLevel = parts == null ? Integer.MAX_VALUE : Math.max(parts[0], 1);
        while (current.preview() < target) {
            if (device.pastDeadline()) return current;
            if (parts == null || parts[1] - parts[0] < perLevel + 1) return current;
            device.tap(EnhanceDialogReader.SELECT_MORE, BUTTON_RADIUS);
            device.pause(1200);
            Dialog next = await(reads::dialog, "the dialog did not read after Select more");
            for (int attempt = 0; attempt < READ_ATTEMPTS && next.preview() != null && next.preview().equals(current.preview()); attempt++) {
                device.pause(READ_PAUSE_MS);
                next = await(reads::dialog, "the dialog did not read after Select more");
            }
            if (next.level() != level || next.preview() == null) {
                throw new Halt(Stop.PREVIEW_MISMATCH, "Select more changed the piece or cleared the selection: " + next);
            }
            if (next.preview().equals(current.preview())) return current;
            if (next.preview() < current.preview() || next.preview() > target) {
                throw new Halt(Stop.PREVIEW_MISMATCH, "expected Select more to go from Lv." + current.preview() + " toward Lv." + target
                        + " and no further, read " + next);
            }
            if (!next.enhanceLit() || !EnhanceDialogReader.SELECT_MORE_LABEL.equals(next.leftButton())) {
                throw new Halt(Stop.PREVIEW_MISMATCH, "the buttons changed after Select more: " + next);
            }
            current = next;
            int[] after = readParts();
            perLevel = after == null || parts == null ? Integer.MAX_VALUE : Math.max(after[0] - parts[0], 1);
            parts = after;
        }
        return current;
    }

    private int[] readParts() {
        for (int attempt = 0; attempt < PARTS_READ_ATTEMPTS; attempt++) {
            int[] parts = reads.partsTile(device.capture());
            if (parts != null) return parts;
            device.pause(READ_PAUSE_MS);
        }
        return null;
    }

    private void report(Raise raise, int level, int reached) {
        RawImageData frame = device.capture();
        EnhanceDialogReader.Gain gain = reads.previewGain(frame);
        int[] parts = reads.partsTile(frame);
        device.previewed(new Preview(raise.hero(), raise.slot(), level, reached, gain == null ? null : gain.stat(),
                gain == null ? null : gain.from(), gain == null ? null : gain.to(), parts == null ? null : parts[0]));
    }

    private void scanFodder() {
        RawImageData frame = device.capture();
        if (!reads.listTop(frame)) {
            device.readFailed(frame, "the fodder list did not open at its top");
            throw new Halt(Stop.LIST_SCAN_FAILED, "the fodder list did not open at its top");
        }
        int seen = 0;
        for (int swipe = 0; swipe < MAX_SWIPES; swipe++) {
            seen += checkTiles(frame);
            if (device.pastDeadline()) throw new Halt(Stop.DEADLINE, "time cap reached during the list scan");
            device.swipe(EnhanceDialogReader.SCROLL_FROM, EnhanceDialogReader.SCROLL_TO, SWIPE_MS);
            device.pause(900);
            RawImageData next = device.capture();
            Integer offset = reads.scrollOffset(frame, next);
            if (!EnhanceDialogReader.provesEveryTile(offset)) {
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

    private void press(Raise raise, int level, int reached) {
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
            if (after != null && after.preview() == null && after.level() == reached) {
                confirm(reached);
                close();
                return;
            }
            if (after != null && after.level() > reached) {
                throw new Halt(Stop.PRESS_UNCONFIRMED, "expected Lv." + reached + " after the press, read " + after);
            }
            GearDetails details = after == null ? reads.details(frame) : null;
            if (details != null && details.enhancement() == reached) {
                confirm(reached);
                return;
            }
            device.pause(READ_PAUSE_MS);
        }
        throw new Halt(Stop.PRESS_UNCONFIRMED, "Lv." + reached + " was not confirmed after the press");
    }

    private void confirm(int reached) {
        try {
            device.pressConfirmed();
        } catch (IOException e) {
            throw new Halt(Stop.PRESS_UNCONFIRMED, "Lv." + reached + " showed but its record could not be cleared: " + e.getMessage());
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
