package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

/**
 * Bear Trap's pet step reads the Pet Skill popup from live 720x1280 captures: red-badge combat
 * tiles only, and Use pressed only while it is blue.
 */
class BearPetCombatSkillsFrameTest {

    @Test
    void findsTheFourCombatSkillsAndSkipsTheGrowthOnes() throws Exception {
        // 2026-11-09: nine skills, four with the red crossed-swords badge, nothing selected.
        BufferedImage frame = load("pet-skill-four-combat-20261109.png");

        assertTrue(BearPetCombatSkills.isPopupOpen(frame));
        assertEquals(List.of(
                        new PointData(141, 452), new PointData(433, 452),
                        new PointData(579, 452), new PointData(141, 598)),
                BearPetCombatSkills.combatTiles(frame));
    }

    @Test
    void greyUseWithNothingSelectedIsNotLive() throws Exception {
        assertEquals(Optional.empty(),
                BearPetCombatSkills.liveUseButton(load("pet-skill-four-combat-20261109.png")));
    }

    @Test
    void aReadyGrowthSkillHasOneCentredUse() throws Exception {
        BufferedImage frame = load("pet-skill-tile-cooldown-20260907.png");

        assertEquals(Optional.of(new PointData(360, 1090)), BearPetCombatSkills.liveUseButton(frame));
        assertEquals(List.of(new PointData(141, 452)), BearPetCombatSkills.combatTiles(frame));
    }

    @Test
    void aReadyCombatSkillPressesTheRightHandUseNotQuickUseOrTheGap() throws Exception {
        // 2026-11-10: Razorbeak selected. A combat skill shows Quick Use (left) and Use (right);
        // the 09:00 run pressed (360,1090), the gap between them, and nothing happened.
        BufferedImage frame = load("pet-skill-combat-selected-20261110.png");

        assertTrue(BearPetCombatSkills.isPopupOpen(frame));
        assertEquals(Optional.of(new PointData(520, 1090)), BearPetCombatSkills.liveUseButton(frame));
        assertEquals(4, BearPetCombatSkills.combatTiles(frame).size());
    }

    @Test
    void aReadyCombatSkillOffersQuickUseButAGrowthSkillOrCooldownDoesNot() throws Exception {
        assertTrue(BearPetCombatSkills.offersQuickUse(load("pet-skill-combat-selected-20261110.png")));
        assertFalse(BearPetCombatSkills.offersQuickUse(load("pet-skill-tile-cooldown-20260907.png")));
        assertFalse(BearPetCombatSkills.offersQuickUse(load("pet-skill-selected-oncooldown-20260907.png")));
        assertFalse(BearPetCombatSkills.offersQuickUse(load("pet-skill-four-combat-20261109.png")));
    }

    @Test
    void theQuickUseConfirmationHasABlueUseAndCoversThePopup() throws Exception {
        // 2026-11-10 13:48: what Quick Use opens. Free; Cancel left, Use right.
        BufferedImage dialog = ImageIO.read(Objects.requireNonNull(
                getClass().getResourceAsStream("/bear/battle-skills-confirmation-20261110.png")));

        assertTrue(BearPetCombatSkills.confirmationUseIsBlue(dialog));
        assertFalse(BearPetCombatSkills.isPopupOpen(dialog));
        assertFalse(BearPetCombatSkills.confirmationUseIsBlue(load("pet-skill-combat-selected-20261110.png")));
    }

    @Test
    void aSkillOnCooldownOrRunningIsNotLive() throws Exception {
        assertEquals(Optional.empty(),
                BearPetCombatSkills.liveUseButton(load("pet-skill-selected-oncooldown-20260907.png")));
        assertEquals(Optional.empty(),
                BearPetCombatSkills.liveUseButton(load("pet-skill-active-effect-20260907.png")));
    }

    @Test
    void theCityIsNotThePopup() throws Exception {
        BufferedImage city = load("city-view-not-island-20260823.png");

        assertFalse(BearPetCombatSkills.isPopupOpen(city));
    }

    private BufferedImage load(String name) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream("/pets/" + name)));
    }
}
