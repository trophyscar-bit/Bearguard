package dev.frostguard.tasks.heroes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.squads.GearEnhancePass.Preview;

class EnhanceReasonTest {

    @Test
    void oneLineGivesThePieceTheStatTheScreenShowsThePartsAndTheWeightedReturn() {
        Preview preview = new Preview("Bahiti", "Goggles", 80, 85, "Marksman Lethality", 60.99, 64.50, 78);

        assertEquals("Gear enhance: Bahiti Goggles +80 to +85 (on role, hero weight 1.14): Marksman Lethality 60.99% to 64.50% (+3.51)"
                + " for 78 parts = 0.0450 per part, x weight = 0.0513", HeroGearForgeRoutine.enhanceReason(preview, Map.of("Bahiti", 1.14)));
    }

    @Test
    void aStatRowOrPartsCountThatDidNotReadSaysSoInsteadOfInventingANumber() {
        String noRow = HeroGearForgeRoutine.enhanceReason(new Preview("Mia", "Boots", 81, 82, null, null, null, 3), Map.of());
        String noParts = HeroGearForgeRoutine.enhanceReason(new Preview("Mia", "Boots", 81, 82, "Lancer Lethality", 61.7, 62.4, null), Map.of("Mia", 0.3));

        assertTrue(noRow.endsWith("the preview's stat row did not read"), noRow);
        assertTrue(noRow.contains("hero weight 0.5"), "an unweighted hero counts as 0.5: " + noRow);
        assertTrue(noParts.endsWith("parts selected did not read"), noParts);
    }
}
