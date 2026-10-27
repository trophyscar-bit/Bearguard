# Crystal Laboratory

Weekly RFC refinement runs on Monday when its target is positive, independently
of the daily discounted RFC purchase setting. The current FC and refined-FC
read regions are fixed to the established 720x1280 game layout; no saved
refined-FC panel fixture is available yet, so OCR accuracy on other layouts is
not established.

Crystal Laboratory entry belongs to navigation. After its Daily-sidebar Go
action, navigation makes one neutral tap at `(470,890)..(490,910)`, then looks
for the static building anchor only in `(330,600)..(500,780)`. It makes at
most two passes, each requiring the Crystal Lab interface to appear. Missing
anchors and missing interfaces retain a local `crystallaboratory` diagnostic
frame. The coordinates are measured for the 720x1280 post-sidebar city view.

The daily Super Refine offer uses the complete white-outline `-50%` badge and
is searched monochromatically only in `(25,775)..(210,1000)`. One detected
Super Refine x1 tap is considered successful only when the weekly refinement
count increases by one and that badge disappears. Any unreadable or conflicting
postcondition is unknown and retries in five minutes rather than scheduling a
daily reset.

Null, blank, or unparseable readings are treated as failed OCR. After exhausting
the bounded retries, the routine saves a local full-screen diagnostic frame to
`logs/snapshot/` and logs its workspace-relative path when possible. Unexpected
screen or required-control misses also retain a frame. Expected missing crystal
claims or discounted offers do not. Frames are not redacted automatically;
review and redact them before sharing.

Weekly OCR, action, and insufficient-FC retries are capped at the next daily
reset. A detected discounted RFC offer whose purchase is not confirmed retries
in five minutes, also capped at that reset. The daily reset is used when the
offer is absent. Refinement taps are followed by an OCR check of the target
level before the routine reports success. Live account-log confirmation and
saved-frame OCR verification remain outstanding.
