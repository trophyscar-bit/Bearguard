package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorBlobFinder;

/**
 * The badge that stays on screen after its tap, from the live 2026-11-13 runs.
 *
 * <p>Each fixture is the 200x420 strip at x=260..460, y=0..420 of a 720x1280 diagnostic frame the
 * routine saved, holding the crafting station at the back of the island and the top of the tree and
 * nothing from the HUD. It is pasted back at its original offset on a neutral canvas so detection
 * runs at real screen coordinates.
 *
 * <p>At 12:25 the routine tapped the station bubble at (358,58) and then the tree bubble; the tree
 * paid out (+581) and the station bubble was back a second later. At 14:00 the same two taps
 * cleared the station and left the tree. The old routine never tapped a leftover again and logged it
 * with a fresh frame on every remaining scan, three times in the 12:25 run alone.
 */
class IslandLeftoverBadgeFrameTest {

    private static final int STRIP_LEFT = 260;
    private static final PointData STATION = new PointData(358, 58);
    private static final PointData TREE = new PointData(364, 352);

    @Test
    void theStationLeftoverIsARealClaimBubbleThatHeldStill() throws Exception {
        List<PointData> found = settledBadges("island-leftover-station-badge-20261113-122507.png",
                "island-leftover-station-badge-20261113-122509.png");

        assertEquals(1, found.size(), "expected only the station bubble: " + found);
        assertTrue(near(found.getFirst(), STATION), "station bubble not where it was logged: " + found);
    }

    @Test
    void theTreeLeftoverIsARealClaimBubbleThatHeldStill() throws Exception {
        List<PointData> found = settledBadges("island-leftover-tree-badge-20261113-140040.png",
                "island-leftover-tree-badge-20261113-140042.png");

        assertEquals(1, found.size(), "expected only the tree bubble, the station having cleared: " + found);
        assertTrue(near(found.getFirst(), TREE), "tree bubble not where it was logged: " + found);
    }

    @Test
    void theStationLeftoverAt1225GetsOneMoreTapAndIsThenGivenUpOnce() throws Exception {
        IslandClaimLedger ledger = new IslandClaimLedger();
        ledger.recordTap(STATION, 0);
        ledger.recordTap(new PointData(356, 352), 2_000);

        List<PointData> leftover = settledBadges("island-leftover-station-badge-20261113-122507.png",
                "island-leftover-station-badge-20261113-122509.png");

        IslandClaimLedger.Plan retry = ledger.plan(leftover, 3_000);
        assertEquals(1, retry.retaps().size(), "the leftover should be tapped once more: " + retry);
        assertTrue(retry.firstTaps().isEmpty(), "a leftover is not a new badge: " + retry);
        assertTrue(retry.newlyGivenUp().isEmpty());
        ledger.recordTap(retry.retaps().getFirst(), 3_000);

        IslandClaimLedger.Plan giveUp = ledger.plan(leftover, 6_000);
        assertFalse(giveUp.tapsAnything(), "never a third tap on the same bubble: " + giveUp);
        assertEquals(1, giveUp.newlyGivenUp().size(), "still there after two taps, so given up: " + giveUp);

        IslandClaimLedger.Plan afterwards = ledger.plan(leftover, 9_000);
        assertFalse(afterwards.tapsAnything());
        assertTrue(afterwards.newlyGivenUp().isEmpty(), "given up once, not reported again on every scan");
        assertEquals(1, ledger.claimedCount(), "only the tree is counted");
    }

    @Test
    void theTreeLeftoverAt1400IsRetriedAndCountedWhenItClears() throws Exception {
        IslandClaimLedger ledger = new IslandClaimLedger();
        ledger.recordTap(STATION, 0);
        ledger.recordTap(TREE, 3_000);

        List<PointData> leftover = settledBadges("island-leftover-tree-badge-20261113-140040.png",
                "island-leftover-tree-badge-20261113-140042.png");

        IslandClaimLedger.Plan retry = ledger.plan(leftover, 6_000);
        assertEquals(1, retry.retaps().size(), "the tree leftover should be tapped once more: " + retry);
        assertTrue(near(retry.retaps().getFirst(), TREE));
        ledger.recordTap(retry.retaps().getFirst(), 6_000);

        IslandClaimLedger.Plan cleared = ledger.plan(List.of(), 8_000);
        assertFalse(cleared.tapsAnything());
        assertEquals(2, ledger.claimedCount(), "both bubbles count once the retry clears the tree");
    }

    @Test
    void aLeftoverIsNotRetriedBeforeTheEarlierRewardsCouldLand() throws Exception {
        IslandClaimLedger ledger = new IslandClaimLedger();
        ledger.recordTap(TREE, 1_000);

        List<PointData> leftover = settledBadges("island-leftover-tree-badge-20261113-140040.png",
                "island-leftover-tree-badge-20261113-140042.png");

        IslandClaimLedger.Plan early = ledger.plan(leftover, 1_000 + IslandClaimLedger.RETAP_MIN_AGE_MILLIS - 1);
        assertFalse(early.tapsAnything(), "too soon to tell a lost tap from a fading bubble: " + early);
        assertEquals(1, early.waiting().size());

        IslandClaimLedger.Plan due = ledger.plan(leftover, 1_000 + IslandClaimLedger.RETAP_MIN_AGE_MILLIS);
        assertEquals(1, due.retaps().size());
    }

    @Test
    void aNewBadgeElsewhereIsStillTappedAlongsideALeftover() throws Exception {
        IslandClaimLedger ledger = new IslandClaimLedger();
        ledger.recordTap(STATION, 0);
        PointData greenhouse = new PointData(464, 965);

        List<PointData> scan = new ArrayList<>(settledBadges(
                "island-leftover-station-badge-20261113-122507.png",
                "island-leftover-station-badge-20261113-122509.png"));
        scan.add(greenhouse);

        IslandClaimLedger.Plan plan = ledger.plan(scan, 3_000);
        assertEquals(List.of(greenhouse), plan.firstTaps());
        assertEquals(1, plan.retaps().size());
    }

    private List<PointData> settledBadges(String earlier, String later) throws Exception {
        return IslandClaimBadges.settled(
                        IslandClaimBadges.candidates(onScreen(earlier)),
                        IslandClaimBadges.candidates(onScreen(later)))
                .stream()
                .map(ColorBlobFinder.Blob::centre)
                .toList();
    }

    private BufferedImage onScreen(String strip) throws Exception {
        BufferedImage crop = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/pets/" + strip), strip));
        BufferedImage screen = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        try {
            g.setColor(new Color(128, 128, 128));
            g.fillRect(0, 0, screen.getWidth(), screen.getHeight());
            g.drawImage(crop, STRIP_LEFT, 0, null);
        } finally {
            g.dispose();
        }
        return screen;
    }

    private static boolean near(PointData found, PointData expected) {
        return Math.abs(found.col() - expected.col()) <= 8 && Math.abs(found.row() - expected.row()) <= 8;
    }
}
