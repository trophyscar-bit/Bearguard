package dev.frostguard.engine.deals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TabNameConsensusTest {

    /** Strip reads of one tab on 2026-10-23; none of them is the name, and every letter is. */
    @Test
    void votesEachLetterAcrossMisreads() {
        assertEquals("Custom Armament Chest", TabNameConsensus.of(List.of("Custom Armamentj Chest",
                "Sustom Armanient Chest", "Custom Armament Ch\uFFFDst", "Custom Armamenti Ch\uFFFDest",
                "\u2018Custom Armament Chest\u2019")));
        assertEquals("Custom Pet Chest", TabNameConsensus.of(List.of("Custom'Pet Chest", "Custom Pet Chest",
                "\u2018Custom Pet Chest")));
        assertEquals("The Top Chief", TabNameConsensus.of(List.of("The Top'Chief", "The Tey Chief", "The Top Chief")));
    }

    @Test
    void tellsSimilarShopsApart() {
        assertTrue(DealCollection.sameName("customarmameng", "customarmamentchwer"));
        assertFalse(DealCollection.sameName("custompetchest", "customarmamentchest"));
        assertFalse(DealCollection.sameName("firecrystalenergy", "firecrystalleappack"));
        assertTrue(DealCollection.sameName("hallofchiet", "hallofchiefs"));
    }
}
