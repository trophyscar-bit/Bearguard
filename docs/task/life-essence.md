# Life Essence Claim Detection

`LifeEssenceRoutine` claims the badges on the player's own My Island screen.
`LifeEssenceCaringRoutine` covers the separate alliance caring flow and is
unaffected by anything here.

Badges appear over the Life Tree and over each crafting station. Players place
their stations wherever they like and the tree does not always have a badge, so
the number and position of badges vary per account and per visit. The routine
therefore scans `CommonGameAreas.ISLAND_CLAIM_BADGE_AREA` and taps what it finds
instead of tapping fixed points.

Template matching is not usable for this screen. The badge bubbles bounce, so a
template cut from a live frame only self-matched at ~0.84 against a 0.90
threshold, and the badge over the tree is different artwork from the ones over
the stations.

The signal is the Life Essence crystal that every badge carries.
`IslandClaimBadges` accepts a `GameColors.isVividGreen` blob whose bounding box
is 28-52 wide, 30-70 tall, and at least 0.45 filled. Green alone does not
identify a badge; the size rules do.

Those numbers come from 29 badge sightings across 15 live island frames: the
crystal covers 913-1476 green pixels in a 35-42 x 37-59 box at 0.54-0.70 fill,
the height varying because the bubble bounces. Everything else green on the same
frames topped out at 214 pixels in a 19x21 box.

Do not calibrate against the bundled `claim.png` and `claimPin.png` templates.
They are cut at a smaller scale than the game renders and measure 22-24 x 33, so
a window derived from them finds nothing on a real frame - which is exactly what
happened on the first attempt here.

The search window deliberately stops short of the top HUD, whose Life Essence
counter carries the same crystal, and short of the chat and bottom navigation
bars.

Claim counts are real counts. Each pass rescans, and a badge found again within
`SAME_BADGE_RADIUS` of one already tapped is logged as a warning rather than
counted a second time, so a screen where claiming silently fails reports zero
instead of a full total.

If the routine ever scans the wrong screen the window keeps it harmless rather
than random: the city view's green villager markers measure 25x25 and 9x52 and
are rejected on both width and area. That is measured on live frames but is not
covered by a committed fixture.

Unsupported: badges that sit outside the search window, and any island layout
where two badges overlap closely enough to merge into one blob.
