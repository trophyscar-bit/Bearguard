package dev.frostguard.tasks.events;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.events.EndlessTrialRoutine.RowState;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndlessTrialRoutineFrameTest {

    private static final String DIR = "/endless-trial/";
    private static final PointData FULL_TL = new PointData(0, 0);
    private static final PointData FULL_BR = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void readsOneAttackAsFirstTierClaimableAndTheRestNotReached() throws IOException {
        List<RowState> rows = rows("daily-rewards-one-attack-20260919.png");

        assertEquals(List.of(RowState.CLAIMABLE, RowState.NOT_REACHED, RowState.NOT_REACHED), rows);
        assertEquals(1, EndlessTrialRoutine.attacksReached(rows));
    }

    @Test
    void readsThreeAttacksAsEveryTierClaimable() throws IOException {
        List<RowState> rows = rows("daily-rewards-all-claimable-20260919.png");

        assertEquals(List.of(RowState.CLAIMABLE, RowState.CLAIMABLE, RowState.CLAIMABLE), rows);
        assertEquals(3, EndlessTrialRoutine.attacksReached(rows));
    }

    @Test
    void readsClaimedTicksAsDoneWithNothingLeftToClaim() throws IOException {
        List<RowState> rows = rows("daily-rewards-all-claimed-20260919.png");

        assertEquals(List.of(RowState.CLAIMED, RowState.CLAIMED, RowState.CLAIMED), rows);
        assertEquals(3, EndlessTrialRoutine.attacksReached(rows));
    }

    @Test
    void findsBossAttackButtonOnlyOnTheBossPopup() throws IOException {
        assertTrue(locate("boss-attack-popup-20260919.png", TemplatesEnum.ENDLESS_TRIAL_ATTACK_BUTTON).isFound());
        assertFalse(locate("formation-deploy-20260919.png", TemplatesEnum.ENDLESS_TRIAL_ATTACK_BUTTON).isFound());
        assertFalse(locate("record-damage-popup-20260919.png", TemplatesEnum.ENDLESS_TRIAL_ATTACK_BUTTON).isFound());
    }

    @Test
    void reusesBearHuntDeployTemplateOnTheFormationScreenOnly() throws IOException {
        ImageSearchResultData deploy = locate("formation-deploy-20260919.png", TemplatesEnum.BEAR_DEPLOY_BUTTON);

        assertTrue(deploy.isFound(), "Deploy should match the formation screen: " + deploy);
        assertTrue(deploy.getPoint().getY() > 1150, "Deploy sits at the bottom of the formation screen: " + deploy);
        assertFalse(locate("boss-attack-popup-20260919.png", TemplatesEnum.BEAR_DEPLOY_BUTTON).isFound());
    }

    @Test
    void findsRecordPopupConfirmOnlyOnTheRecordPopup() throws IOException {
        assertTrue(locate("record-damage-popup-20260919.png", TemplatesEnum.ENDLESS_TRIAL_RESULT_CONFIRM_BUTTON).isFound());
        assertFalse(locate("formation-deploy-20260919.png", TemplatesEnum.ENDLESS_TRIAL_RESULT_CONFIRM_BUTTON).isFound());
        assertFalse(locate("daily-rewards-all-claimable-20260919.png", TemplatesEnum.ENDLESS_TRIAL_RESULT_CONFIRM_BUTTON).isFound());
    }

    @Test
    void findsUnselectedEndlessTrialTabInTheEventStrip() throws IOException {
        ImageSearchResultData tab = OpenCvPatternLocator.locatePattern(resource("events-tab-strip-unselected-20260919.png"),
                TemplatesEnum.ENDLESS_TRIAL_TAB, new PointData(0, 80), new PointData(720, 210), 90);

        assertTrue(tab.isFound(), "Unselected Endless Trial tab should match: " + tab);
        assertFalse(locate("daily-rewards-all-claimable-20260919.png", TemplatesEnum.ENDLESS_TRIAL_TAB).isFound());
    }

    private static ImageSearchResultData locate(String frame, TemplatesEnum template) throws IOException {
        return OpenCvPatternLocator.locatePattern(resource(frame), template, FULL_TL, FULL_BR, 85);
    }

    private static List<RowState> rows(String frame) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(resource(frame)));
        return EndlessTrialRoutine.classifyRewardRows(image);
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = EndlessTrialRoutineFrameTest.class.getResourceAsStream(DIR + name)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + name).readAllBytes();
        }
    }
}
