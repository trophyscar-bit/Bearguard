# Alliance Tech

R4 accounts can open Alliance Tech on a researchable node instead of the
alliance-recommended node. The green research arrows do not identify the
recommended target; only the green thumbs-up template does.

The routine visits each category once. It checks that category's remembered
position first and, if the marker is absent, normalizes the tree to the top with
five swipes and scans downward with at most five overlapping swipes. The smaller
gestures retain the same total travel as the previous three-swipe traversal while
exposing more intermediate viewports. Category
tabs use fixed named regions because the tab artwork changes between selected
and unselected states and no stable category templates exist.

Saved frames from 2026-08-10 cover both observed states: the Growth frame with
researchable arrows must not match the thumbs-up template, while the Battle
frame with the recommended Lancer Lethality node must match at the runtime
threshold. Live navigation and donation confirmation remain required before
merge readiness.

## Fallback when nothing is recommended

When no thumbs-up marker exists in any tree, the routine donates to the Battle
tree's "Long Live Our Alliance" node instead of failing, so a filled pool is
spent rather than banked until it caps. The monument artwork is shared with
Growth's "Fertile Land Expedition", so the fallback scan re-selects Battle and
searches only there; `techMonumentNode.png` is cropped from a native 720x1280
Growth frame and matched against the 2026-09-03 Battle frame, which carries no
recommendation. The fallback still fails the pass when the node is off-screen
after the bounded scan.

Unverified: the node is `0/1` in the supplied frame. Whether a completed
"Long Live Our Alliance" still opens a donation popup has not been observed, so
a maxed node could leave the 25 donation taps landing on a popup with no donate
button.
