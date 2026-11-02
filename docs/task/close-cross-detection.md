# Close-cross detection

`CloseCrossDetector` is a reusable vision primitive for locating light X-shaped
close controls. Callers select a predefined screen region or pass an explicit
inclusive area. Results contain bounds, center, and score in full-frame
coordinates; the detector never taps or decides whether the current screen is
safe to dismiss.

The detector uses multi-scale grayscale OpenCV template matching with masks
derived from each template's bright close glyph. The masks exclude changing
offer artwork and header colors without tying the vision module to a task or
overlay lifecycle. Invalid normalized-correlation cells from uniform regions
are ignored. The existing startup overlay dismissal remains separate and
retains its own narrow search, threshold, and bounded tap attempts.

## Evidence and limits

Initialization now uses the shared detector in its measured
`(540, 20)`–`(719, 280)` area, after higher-priority startup blockers have been
checked. The previous `(540, 65)`–`(680, 240)` bounds cannot contain the
Craftsman's Treasure X measured at `(637, 202)`–`(691, 256)` on 10 October
2026. Its white glyph on a blue canopy also does not score with the original
masked template, so the detector adds a second grayscale masked template while
retaining the 78% acceptance threshold. Initialization accepts candidates only
through y=240 by center point, excluding lower Welcome back decoration exposed
by the larger rectangle. A full
upper-right quarter produced a false match on Welcome back in saved-frame tests,
so the narrower custom-area overload is used. The existing three dismissal
limit and fresh home/world postcondition remain in place. The runtime passes the
raw emulator frame directly to avoid an intermediate image conversion.

The reviewed Myriad Bazaar frame from 17 October 2026 adds a third masked
template for its compact white X, centred around `(680, 43)`. The old y=65
boundary excludes that control; the y=20 boundary detects it while retaining
the right-side and y=240 limits. Startup treats that cross as a generic
closeable-overlay control; it does not need to identify Myriad Bazaar before
tapping. The generic high band remains available for other future overlays,
subject to the same bounded dismissal and fresh home/world postcondition.

The saved-frame set contains seven positive close-control crops spanning
top-right and middle-right positions and one nearby green plus negative. Earlier
source captures contained identifying account or map details; fixtures retain
only manually reviewed crops around the relevant controls. The detector and
startup integration have saved-frame tests. The Android launcher, mandatory
update, resource-download, and Welcome back frames remain explicit startup
negatives, while offer and Craftsman's Treasure frames prove existing overlays
still detect after the high-band extension. Annotated frames are in
`tools/close-cross-detection/evidence/`.

The initial 78% threshold is empirical for this small set. The masked live
Charm Master Pack regression frame scores 88.1% at its detected close control;
the green plus remains rejected. This is not broad validation of other icon
shapes. The startup integration still needs an isolated live run and account-log
confirmation. Consumers remain responsible for their own screen-state and
action-safety decisions.
