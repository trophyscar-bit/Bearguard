package dev.frostguard.vision.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SegmentedControlTest {

    private static final int EPIC_X = 187;
    private static final int MYTHIC_X = 532;
    private static final int OPTION_Y = 223;

    @Test
    void findsBothTiersWithEpicOpenOnTheWeeklyBenefitsCard() throws Exception {
        SegmentedControl.Control control = locate("segmented-weekly-benefits-epic-open.png");

        assertTiers(control);
        assertEquals(0, control.selectedIndex(), control.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "segmented-weekly-benefits-mythic-open.png",
            "segmented-weekly-benefits-mythic-open-scan.png",
            "segmented-intel-monthly-mythic-open.png",
            "segmented-intel-monthly-mythic-open-scan.png"})
    void findsBothTiersWithMythicOpen(String name) throws Exception {
        SegmentedControl.Control control = locate(name);

        assertTiers(control);
        assertEquals(1, control.selectedIndex(), control.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "no-segmented-fire-crystal-leap-pack.png",
            "no-segmented-fire-crystal-weekly-card.png",
            "no-segmented-fire-crystal-value-pack.png",
            "no-segmented-gem-bonus.png",
            "no-segmented-hero-rally.png",
            "no-segmented-blue-weekly-cards-scrolled.png"})
    void reportsNothingOnPagesWithoutTheControl(String name) throws Exception {
        Optional<SegmentedControl.Control> control = SegmentedControl.locate(load(name));

        assertTrue(control.isEmpty(), () -> name + " -> " + control.get());
    }

    private static SegmentedControl.Control locate(String name) throws Exception {
        Optional<SegmentedControl.Control> control = SegmentedControl.locate(load(name));
        assertTrue(control.isPresent(), name);
        return control.get();
    }

    private static void assertTiers(SegmentedControl.Control control) {
        assertEquals(2, control.options().size(), control.toString());
        assertNear(control.options().get(0), EPIC_X, OPTION_Y);
        assertNear(control.options().get(1), MYTHIC_X, OPTION_Y);
    }

    private static void assertNear(SegmentedControl.Option option, int centreX, int centreY) {
        assertTrue(Math.abs(option.centreX() - centreX) <= 3 && Math.abs(option.centreY() - centreY) <= 3,
                option.toString());
    }

    private static BufferedImage load(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(SegmentedControlTest.class.getResourceAsStream("/deals/" + name), name));
    }
}
