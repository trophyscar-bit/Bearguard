# City Shops Daily Chest

The Bakery, Milk Tea and Vinyl shop buildings each show the same golden chest bubble while the
day's reward is unclaimed (reset UTC 00:00, collected manually). Tapping any one bubble opens
"Daily Building Rewards", which pays all three buildings at once; "Tap anywhere to exit" closes it
and the bubbles disappear. Checked live on 2026-11-13: bakery 5 of a bottle item, milk tea a 1K gem
bag, vinyl a mystery chest.

Panels (checked live 2026-11-13): Vinyl Shop offers Daily Rewards (an info table of the reward pool by furnace level, not a claim)
and Play Vinyl, a music player with no rewards. The Bakery (Aubree) and Milk Tea Shop (Rodney) each offer only a
Daily Rewards info button. Nothing beyond the daily chest needs automating.

Navigation: the shops are not in the Shop footer or in any sidebar tab (City, Wilderness, Daily
checked live 2026-11-13), and `World` then `Return to City` does not recenter. The city map has hard
edges: dragging finger-down 26 times pins the north (static fog, frame change 0.5 against noise) and
finger-left 30 times pins the east, and the south also clamps. From that north-east corner, drags of 400
px at 1000 ms repeat to about 100 px. Two steps south and three west shows the Bakery and Milk Tea at the
top-left; one step north centres the Milk Tea; two more west and one north bring in the Vinyl Shop. The
routine searches for the bubble after each of those stops. A bubble that is not found is unknown, not
claimed: recheck in 3 hours, never the next reset. A claim counts only if the bubble is gone after the
popup is dismissed; otherwise it retries in 1 hour. A success reschedules to the next UTC midnight plus
2-12 minutes.

The template is the chest only, cut from the vinyl bubble. Across frames not used for the cut it scores
93-97 against 51-60 without a bubble; the threshold is 85.

Not yet verified: the routine end to end on a live run. The route was walked by hand, not by the routine. Saved-frame coverage is the bubble template only.
