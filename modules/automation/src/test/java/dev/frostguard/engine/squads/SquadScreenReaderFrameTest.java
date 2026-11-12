package dev.frostguard.engine.squads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.HeroGear;
import dev.frostguard.api.squads.HeroSkill;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.TroopOverview;
import dev.frostguard.api.squads.TroopStock;

class SquadScreenReaderFrameTest {

    @Test
    void readsTheHeroHeaderOnTheStatsTab() throws Exception {
        RawImageData frame = frame("hero-stats-tab.png");

        HeroScreenReader.Detail detail = HeroScreenReader.readDetail(frame);

        assertTrue(HeroScreenReader.isHeroPage(frame));
        assertEquals("Mia", detail.name());
        assertEquals("SSR", detail.rarity());
        assertEquals(1_614_639L, detail.power());
        assertEquals(77, detail.level());
        assertEquals(10, detail.escorts());
        assertEquals(13_350L, detail.troopCapacity());
        // Three stars lit and the fourth about two thirds lit on this frame.
        assertEquals(3.7, detail.stars());
    }

    @Test
    void readsBothSectionsOfTheOverallStatsPopup() throws Exception {
        HeroScreenReader.Stats stats = HeroScreenReader.readStats(frame("hero-overall-stats.png"));

        assertNotNull(stats);
        assertEquals("Lancer", stats.troopClass());
        assertEquals(Map.of("Hero Attack", 3_528L, "Hero Defense", 2_780L, "Hero Health", 31_541L,
                "Escort Attack", 1_175L, "Escort Defense", 926L, "Escort Health", 10_513L), stats.exploration());
        assertEquals(Map.of("Attack", 175.02, "Defense", 175.02, "Lethality", 148.70, "Health", 65.56),
                stats.expedition());
    }

    @Test
    void aTroopStatTheGameLeavesOutIsZero() throws Exception {
        // Jessie has no gear, so the pop-up lists Attack, Defense and Lethality and no Health line.
        HeroScreenReader.Stats stats = HeroScreenReader.readStats(frame("hero-stats-no-health-line.png"));
        assertEquals("Lancer", stats.troopClass());
        assertEquals(Map.of("Attack", 140.11, "Defense", 140.11, "Lethality", 3.33, "Health", 0.0), stats.expedition());
    }

    @Test
    void statsPopupIsUnknownWhenItIsNotOpen() throws Exception {
        assertNull(HeroScreenReader.readStats(frame("hero-stats-tab.png")));
    }

    @Test
    void readsTheSelectedSkillTitleLevelAndCurrentEffect() throws Exception {
        HeroSkill skill = HeroScreenReader.readSelectedSkill(frame("hero-skill-expedition-1.png"));

        assertNotNull(skill);
        assertEquals("Bad Luck Streak", skill.name());
        assertEquals(4, skill.level());
        assertEquals("Grants all troops' attack a 50% chance of cursing the target,"
                + " increasing their damage taken by 40%.", skill.description());
    }

    @Test
    void readsGearEnhancementAndLeavesAnUnmasteredPieceWithoutLevel() throws Exception {
        RawImageData frame = frame("hero-gear.png");

        List<HeroGear> gear = HeroScreenReader.readGear(frame);
        HeroScreenReader.Exclusive exclusive = HeroScreenReader.readExclusive(frame);

        assertEquals(List.of(new HeroGear(1, 100, 6), new HeroGear(2, 55, 1),
                new HeroGear(3, 50, null), new HeroGear(4, 81, 5)), gear);
        assertEquals(new HeroScreenReader.Exclusive("Fate Crystal", 1), exclusive);
    }

    @Test
    void readsTroopsPreviewTotalsAndEveryTroopLine() throws Exception {
        TroopOverview troops = TroopsScreenReader.readPreview(frame("troops-preview.png"));

        assertNotNull(troops);
        assertEquals("422.3K/553.8K", troops.totalTroops());
        assertEquals("0/153.3K", troops.injured());
        assertEquals(List.of(new TroopStock("Apex Infantry", "Infantry", 182_184L),
                new TroopStock("Apex Marksman", "Marksman", 190_078L),
                new TroopStock("Apex Lancer", "Lancer", 181_545L)), troops.stocks());
    }

    @Test
    void troopsPreviewIsUnknownOnAnotherScreen() throws Exception {
        assertNull(TroopsScreenReader.readPreview(frame("deploy-slot-1.png")));
    }

    @Test
    void readsASavedSquadIncludingALoneDigitTroopCount() throws Exception {
        RawImageData frame = frame("deploy-slot-1.png");

        SavedSquad squad = TroopsScreenReader.readDeploy(frame, 1);

        assertTrue(TroopsScreenReader.isDeploy(frame));
        assertEquals(List.of(77, 77, 77), squad.heroLevels());
        assertEquals(List.of(1L, 1L, 69_696L), squad.troops());
        assertEquals("69,698/126,850", squad.capacity());
        assertEquals(71_545_169L, squad.power());
    }

    @Test
    void readsTheTwelfthSavedSquad() throws Exception {
        SavedSquad squad = TroopsScreenReader.readDeploy(frame("deploy-slot-12.png"), 12);

        assertEquals(List.of(80, 78, 77), squad.heroLevels());
        assertEquals(List.of(30_000L, 25_000L, 60_000L), squad.troops());
        assertEquals(123_960_118L, squad.power());
    }

    @Test
    void findsOnlyFullyVisibleGridCardsAndSkipsUnownedOnes() throws Exception {
        RawImageData top = frame("heroes-grid-top.png");
        RawImageData bottom = frame("heroes-grid-bottom.png");

        List<HeroCards.Card> topCards = HeroCards.gridCards(top);
        List<HeroCards.Card> bottomCards = HeroCards.gridCards(bottom);

        assertEquals(12, topCards.size());
        assertEquals(new HeroCards.Card(33, 110), topCards.getFirst());
        assertTrue(topCards.stream().allMatch(card -> HeroCards.isOwned(top, card)));
        assertEquals(11, bottomCards.size());
        // The last row holds one owned hero and two unowned shard cards.
        assertEquals(9, bottomCards.stream().filter(card -> HeroCards.isOwned(bottom, card)).count());
        assertFalse(HeroCards.isOwned(bottom, bottomCards.get(9)));
        assertFalse(HeroCards.isOwned(bottom, bottomCards.get(10)));
    }

    @Test
    void identifiesSavedSquadHeroesFromGridPortraits() throws Exception {
        RawImageData grid = frame("heroes-grid-top.png");
        List<HeroCards.Portrait> gallery = new ArrayList<>();
        for (HeroCards.Card card : HeroCards.gridCards(grid)) gallery.add(HeroCards.portrait(grid, card));

        assertEquals(List.of(5, 3, 4), matches("deploy-slot-1.png", gallery));
        assertEquals(List.of(11, 2, 4), matches("deploy-slot-2.png", gallery));
        assertEquals(List.of(2, 1, 3), matches("deploy-slot-12.png", gallery));
    }

    @Test
    void aPortraitMissingFromTheGalleryStaysUnmatched() throws Exception {
        RawImageData grid = frame("heroes-grid-top.png");
        List<HeroCards.Portrait> gallery = new ArrayList<>();
        List<HeroCards.Card> cards = HeroCards.gridCards(grid);
        // Drop the hero in slot 1's first card (grid index 5) from the gallery.
        for (int i = 0; i < cards.size(); i++) {
            if (i != 5) gallery.add(HeroCards.portrait(grid, cards.get(i)));
        }

        RawImageData deploy = frame("deploy-slot-1.png");
        HeroCards.Portrait first = HeroCards.portrait(deploy, HeroCards.deployCards().getFirst());
        assertEquals(-1, HeroCards.bestMatch(first, gallery));
        assertFalse(gallery.isEmpty());
    }

    @Test
    void ignoresParticleJunkAfterTheSkillLevel() throws Exception {
        HeroSkill skill = HeroScreenReader.readSelectedSkill(frame("hero-skill-junk-after-level.png"));

        assertNotNull(skill);
        assertEquals("Snow's Grace", skill.name());
        assertEquals(5, skill.level());
        assertEquals("Molly creates an avalanche that clouds enemy sights, granting a 40% chance of reducing"
                + " all troops' Damage Taken by 50%.", skill.description());
    }

    @Test
    void readsASkillWithFireParticlesBehindIt() throws Exception {
        HeroSkill skill = HeroScreenReader.readSelectedSkill(frame("hero-skill-particles.png"));

        assertNotNull(skill);
        assertEquals("Pyromaniac", skill.name());
        assertEquals(5, skill.level());
    }

    @Test
    void keepsHyphenatedHeroNamesAndDropsStrayMarks() throws Exception {
        HeroScreenReader.Detail hyphen = HeroScreenReader.readDetail(frame("hero-name-hyphen.png"));
        HeroScreenReader.Detail stray = HeroScreenReader.readDetail(frame("hero-name-stray-mark.png"));

        assertEquals("Seo-yoon", hyphen.name());
        assertEquals("SR", hyphen.rarity());
        assertEquals("Ling Xue", stray.name());
        assertEquals(320_578L, stray.power());
    }

    @Test
    void readsRarityFromTheBadgeColour() throws Exception {
        assertEquals("R", HeroScreenReader.badgeRarity(frame("hero-rare-rarity.png")));
        assertEquals("SR", HeroScreenReader.badgeRarity(frame("hero-name-hyphen.png")));
        assertEquals("SSR", HeroScreenReader.badgeRarity(frame("hero-stats-tab.png")));
        assertEquals("R", HeroScreenReader.readDetail(frame("hero-rare-rarity.png")).rarity());
    }

    @Test
    void gearArtBesideThePlusSignIsNotReadAsADigit() throws Exception {
        assertEquals(List.of(new HeroGear(1, 80, 4), new HeroGear(2, 40, 1),
                new HeroGear(3, 40, 1), new HeroGear(4, 78, 4)), HeroScreenReader.readGear(frame("hero-gear-plus-edge.png")));
        assertEquals(78, HeroScreenReader.enhancement("+78", "778"));
        assertEquals(40, HeroScreenReader.enhancement("40", "+40"));
        assertNull(HeroScreenReader.enhancement("4", ""));
        assertNull(HeroScreenReader.enhancement("", "778"));
    }

    @Test
    void readsBadgesThePlainReadMissesAndNoMasteryWithoutALevelLabel() throws Exception {
        // Flint: +50 (unmastered), +78 Lv.5, +78 Lv.5, +50 Lv.1.
        assertEquals(List.of(new HeroGear(1, 50, null), new HeroGear(2, 78, 5),
                new HeroGear(3, 78, 5), new HeroGear(4, 50, 1)), HeroScreenReader.readGear(frame("hero-gear-white-badges.png")));
    }

    @Test
    void bareGearTilesHaveNoEnhancement() throws Exception {
        assertTrue(HeroScreenReader.readGear(frame("hero-gear-bare.png")).stream()
                .allMatch(g -> g.enhancement() == null && g.mastery() == null));
    }

    @Test
    void aNameOneLetterOffAKnownHeroIsThatHero() {
        assertEquals("Natalia", HeroScreenReader.snapToKnownHero("Nataliap"));
        assertEquals("Sergey", HeroScreenReader.snapToKnownHero("Sergay"));
        assertEquals("Seo-yoon", HeroScreenReader.snapToKnownHero("Seo-yoon"));
        // Two letters off, or a new hero: kept as read.
        assertEquals("Nataliapq", HeroScreenReader.snapToKnownHero("Nataliapq"));
        assertEquals("Zephyr", HeroScreenReader.snapToKnownHero("Zephyr"));
        assertNull(HeroScreenReader.snapToKnownHero(null));
    }

    @Test
    void onlyTheFirstDigitAfterLvIsTheSkillLevel() throws Exception {
        HeroSkill skill = HeroScreenReader.readSelectedSkill(frame("hero-skill-digits-after-level.png"));

        assertNotNull(skill);
        assertEquals("Soothing Dance", skill.name());
        assertEquals(2, skill.level());
    }

    @Test
    void readsALowercaseOrGluedLevelInTheSkillTitle() throws Exception {
        HeroSkill lower = HeroScreenReader.readSelectedSkill(frame("hero-skill-lowercase-level.png"));
        HeroSkill glued = HeroScreenReader.readSelectedSkill(frame("hero-skill-glued-words.png"));

        assertNotNull(lower);
        assertEquals("Super Snowball", lower.name());
        assertEquals(5, lower.level());
        assertNotNull(glued);
        assertEquals("Youthful Rage", glued.name());
        assertEquals(5, glued.level());
    }

    @Test
    void squadWithNoTroopsReadsZeroesAndACleanCapacity() throws Exception {
        SavedSquad squad = TroopsScreenReader.readDeploy(frame("deploy-slot-5-empty-troops.png"), 5);

        assertEquals(List.of(0L, 0L, 0L), squad.troops());
        assertEquals("0/126,970", squad.capacity());
    }

    @Test
    void capacityKeepsItsLastDigitBesideTheWarningIcon() throws Exception {
        SavedSquad squad = TroopsScreenReader.readDeploy(frame("deploy-slot-4.png"), 4);

        assertEquals("126,890/126,890", squad.capacity());
        assertEquals(List.of(63_445L, 38_067L, 25_378L), squad.troops());
    }

    @Test
    void oneUnreadCountIsTheCapacityTotalLessTheOthers() {
        List<Long> troops = new ArrayList<>(java.util.Arrays.asList(1_268L, null, 124_314L));
        TroopsScreenReader.fillOneMissingCount(troops, 126_850L);
        assertEquals(List.of(1_268L, 1_268L, 124_314L), troops);

        List<Long> two = new ArrayList<>(java.util.Arrays.asList(null, null, 5L));
        TroopsScreenReader.fillOneMissingCount(two, 10L);
        assertEquals(java.util.Arrays.asList(null, null, 5L), two);
    }

    private static List<Integer> matches(String resource, List<HeroCards.Portrait> gallery) throws Exception {
        RawImageData deploy = frame(resource);
        List<Integer> found = new ArrayList<>();
        for (HeroCards.Card card : HeroCards.deployCards()) {
            found.add(HeroCards.bestMatch(HeroCards.portrait(deploy, card), gallery));
        }
        return found;
    }

    private static RawImageData frame(String name) throws Exception {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                SquadScreenReaderFrameTest.class.getResourceAsStream("/squads/" + name)));
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
