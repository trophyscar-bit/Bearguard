package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The 27 Oct startup blocker: a restart that found the game on the Trek landing, where three
 * Android Backs changed nothing and the game was closed. The landing's own back arrow is found;
 * the city home screen and a dimmed dialog are not mistaken for it.
 */
class GameBackArrowFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void theTrekLandingThatBlockedStartupShowsTheBackArrow() throws IOException {
        assertTrue(arrowOn("startup-trek-landing.png"));
        assertTrue(arrowOn("academy.png"));
        assertTrue(arrowOn("skill-card-lv2.png"));
    }

    @Test
    void homeAndDimmedDialogsDoNotLookLikeTheBackArrow() throws IOException {
        assertFalse(arrowOn("sidebar-expert-row.png"));
        assertFalse(arrowOn("learn-dialog-2h.png"));
    }

    private boolean arrowOn(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/dawnacademy/" + name), name)) {
            return OpenCvPatternLocator.locatePattern(in.readAllBytes(), TemplatesEnum.GAME_BACK_ARROW.getTemplate(),
                    InitializeRoutine.GAME_BACK_ARROW_AREA.topLeft(), InitializeRoutine.GAME_BACK_ARROW_AREA.bottomRight(),
                    InitializeRoutine.GAME_BACK_ARROW_THRESHOLD).isFound();
        }
    }
}
