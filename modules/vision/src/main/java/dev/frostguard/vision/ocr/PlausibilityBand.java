package dev.frostguard.vision.ocr;

/**
 * The "decline rather than guess" OCR sanity check used across the codebase: a fresh numeric
 * reading is rejected as a likely misread, rather than trusted, when it jumps too far from the
 * last known-good value for the same field. Real values (stockpiles, power, gems) change
 * gradually between reads; the misreads actually observed live (a dropped decimal point, a
 * covering popup, a wrong screen region) jump far outside any gradual-change band.
 *
 * <p>matt/2026-08-19, Dave's #250 review: bg_telemetry.java and ResourceStockpileRoutine.java each
 * hand-rolled their own copy of this exact ratio-band math with no test coverage. Extracted here so
 * both share one implementation that's actually testable -- {@code bg_telemetry.java} lives under
 * {@code examples/custom-tasks/}, outside the Maven module tree, so a private method on it could
 * never get real JUnit coverage; this class can.</p>
 */
public final class PlausibilityBand {

    private PlausibilityBand() {}

    /**
     * Returns {@code true} when {@code candidate} sits within {@code [1/maxRatio, maxRatio]} of
     * {@code previous} -- i.e. neither more than {@code maxRatio}x larger nor {@code maxRatio}x
     * smaller. A {@code previous} of zero or negative has nothing meaningful to compare against and
     * is always treated as plausible (there's no "last known-good" to jump away from).
     *
     * @param maxRatio must be {@code > 1.0} (e.g. 1.5 for a ±50% band); the symmetric lower bound
     *                 is derived as {@code 1.0 / maxRatio}
     */
    public static boolean isPlausible(long candidate, long previous, double maxRatio) {
        if (previous <= 0) {
            return true;
        }
        double ratio = (double) candidate / (double) previous;
        double minRatio = 1.0 / maxRatio;
        return ratio <= maxRatio && ratio >= minRatio;
    }
}
