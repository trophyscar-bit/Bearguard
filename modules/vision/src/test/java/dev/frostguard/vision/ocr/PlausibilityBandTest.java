package dev.frostguard.vision.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dave's #250 review: "the fixed ±50% plausibility rule still has no focused behavioral tests and
 * can reject legitimate changes while accepting smaller OCR errors." Covers the real band used
 * live (maxRatio=1.5, matching bg_telemetry.java's SANITY_BAND_MAX_RATIO), its boundaries, both
 * directions, and the no-previous-value pass-through.
 */
class PlausibilityBandTest {

    private static final double MAX_RATIO = 1.5; // matches bg_telemetry.java's SANITY_BAND_MAX_RATIO

    @Test
    void identicalValueIsAlwaysPlausible() {
        assertTrue(PlausibilityBand.isPlausible(100, 100, MAX_RATIO));
    }

    @Test
    void gradualIncreaseWithinTheBandIsPlausible() {
        // +40% -- a real gradual gain between reads, well inside the ±50% band
        assertTrue(PlausibilityBand.isPlausible(140, 100, MAX_RATIO));
    }

    @Test
    void gradualDecreaseWithinTheBandIsPlausible() {
        // -30% -- a real spend/loss between reads
        assertTrue(PlausibilityBand.isPlausible(70, 100, MAX_RATIO));
    }

    @Test
    void exactUpperBoundaryIsPlausible() {
        // Exactly 1.5x -- the boundary itself must be inclusive (<=, not <).
        assertTrue(PlausibilityBand.isPlausible(150, 100, MAX_RATIO));
    }

    @Test
    void exactLowerBoundaryIsPlausible() {
        // Exactly 1/1.5x -- the symmetric lower boundary, also inclusive.
        assertTrue(PlausibilityBand.isPlausible(667, 1000, MAX_RATIO));
    }

    @Test
    void justAboveTheUpperBoundaryIsRejected() {
        assertFalse(PlausibilityBand.isPlausible(151, 100, MAX_RATIO));
    }

    @Test
    void justBelowTheLowerBoundaryIsRejected() {
        assertFalse(PlausibilityBand.isPlausible(660, 1000, MAX_RATIO));
    }

    @Test
    void realLiveObservedMisreadIsRejected() {
        // matt live, 2026-08-19: steel jumped 1,174,000 -> 839,000,000 (ratio ~714.6x), the actual
        // misread that slipped through ResourceStockpileRoutine's unbounded trust-streak escape
        // hatch. Confirms this band alone would have caught it as implausible on the first read.
        assertFalse(PlausibilityBand.isPlausible(839_000_000L, 1_174_000L, MAX_RATIO));
    }

    @Test
    void realLiveObservedDroppedDecimalMisreadIsRejected() {
        // The documented "~1.7-1.8x" misread class from bg_telemetry.java's own header comment --
        // outside the ±50% band, which is exactly why the band exists at that width.
        assertFalse(PlausibilityBand.isPlausible(177, 100, MAX_RATIO));
    }

    @Test
    void zeroPreviousValueIsAlwaysPlausible() {
        // Nothing to compare against yet (first-ever sample) -- must pass through, not reject.
        assertTrue(PlausibilityBand.isPlausible(500, 0, MAX_RATIO));
    }

    @Test
    void negativePreviousValueIsAlwaysPlausible() {
        // Defensive: a corrupt/negative stored value has nothing meaningful to compare against.
        assertTrue(PlausibilityBand.isPlausible(500, -1, MAX_RATIO));
    }

    @Test
    void zeroCandidateAgainstAPositivePreviousIsRejected() {
        // A read of zero against any real positive previous value is always outside a >1.0 band
        // (ratio 0.0), which is correct -- a stockpile/power reading doesn't actually hit exactly
        // zero between two normal reads a few hours apart.
        assertFalse(PlausibilityBand.isPlausible(0, 100, MAX_RATIO));
    }

    @Test
    void tighterBandRejectsAChangeALooserBandWouldAccept() {
        // Same candidate/previous pair, different maxRatio -- confirms the band width is genuinely
        // parameterized rather than hardcoded, and that policy tightness has a real, testable effect.
        assertFalse(PlausibilityBand.isPlausible(130, 100, 1.2));
        assertTrue(PlausibilityBand.isPlausible(130, 100, 1.5));
    }
}
