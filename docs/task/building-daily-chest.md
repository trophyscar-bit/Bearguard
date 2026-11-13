# City Shops Daily Chest

The Bakery, Milk Tea and Vinyl shop buildings each show the same golden chest bubble while the
day's reward is unclaimed (reset UTC 00:00, collected manually). Tapping any one bubble opens
"Daily Building Rewards", which pays all three buildings at once; "Tap anywhere to exit" closes it
and the bubbles disappear. Checked live on 2026-11-13: bakery 5 of a bottle item, milk tea a 1K gem
bag, vinyl a mystery chest.

Vinyl Shop's own panel offers Daily Rewards (an info table of the reward pool by furnace level,
not a claim) and Play Vinyl (not yet explored). Bakery and Milk Tea panels are unexplored.

Navigation is the fragile part. The shops are not in the Shop footer or the sidebar rows, and the city
camera stays wherever the last routine left it. `World` then `Return to City` does not recenter.
The routine therefore looks at the current view, then makes a bounded 12-pan spiral. It stops at the
first bubble. A bubble that is not found is unknown, not claimed: the recheck is 3 hours, never the
next reset. A claim counts only if the bubble is gone after the popup is dismissed; otherwise it
retries in 1 hour. A successful claim reschedules to the next UTC midnight plus a 2-12 minute pad.

The template is the chest only, cut from the vinyl bubble. Across frames not used for the cut it scores
93-97 against 51-60 without a bubble; the threshold is 85.

Not yet verified: that the 12-pan spiral reaches the shops from an arbitrary starting camera, and the
routine on a live run. Saved-frame coverage is the bubble template only.
