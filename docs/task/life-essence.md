# Life Essence Claim Detection

`LifeEssenceRoutine` claims the badges on the player's own My Island screen.
`LifeEssenceCaringRoutine` covers the separate alliance caring flow and is
unaffected by anything here.

Badges appear over the Life Tree and over each crafting station. Players place
their stations wherever they like and the tree does not always carry a badge, so
the number and position of badges vary per account and per visit. Nothing in the
detection is position-based: the island is scanned and whatever is found is
tapped.

Template matching is not usable for this screen. The badge bubbles bounce, so a
template cut from a live frame only self-matched at ~0.84 against a 0.90
threshold, and the badge over the tree is different artwork from the ones over
the stations.

## Three independent checks

A tap needs all of the following, because no one of them is sufficient.

**The right screen.** `onIslandScreen` requires the Life Essence counter in the
top bar, which is the same crystal artwork as the badges and appears on no other
screen the routine can reach. Without it the detector would work on whatever a
failed navigation left behind, and the world map's marching panel carries a
36x37 green gathering icon at 0.46 fill that passes every crystal rule - tapping
it starts a march. Across 30 live frames the counter was present on every island
frame and absent from every world and city frame.

**The right shape.** `IslandClaimBadges` accepts a `GameColors.isVividGreen`
blob 28-52 wide, 30-70 tall, at least 0.45 filled, of at least 600 pixels. From
32 badge sightings across live frames the crystal covers 913-1476 pixels in a
35-42 x 37-59 box at 0.49-0.70 fill, the height varying because the bubble
bounces and the tree badge is partly covered by the game's tutorial hand. Every
other green thing on those island frames topped out at 214 pixels in a 19x21
box. Green alone does not identify a badge; the size rules do.

**Not moving.** Claiming sends a reward crystal flying from the badge up to the
counter. It is the same colour and size with no bubble around it, so a scan
landing mid-flight would count empty sky as a claim and tap it. Each scan
therefore compares two captures 700ms apart and keeps only blobs that held their
place: a badge bounces at most 8px in that interval, a reward crystal covers
roughly 30px.

## The search window

`CommonGameAreas.ISLAND_CLAIM_BADGE_AREA` is `(0,200)-(720,1100)`. Both edges
are load-bearing and neither should be widened.

The top edge sits below the HUD counter, which measures 31x45 at 0.58 fill and
passes every shape rule - excluding it is the only thing that stops the routine
tapping the currency counter. It also sits above the badge over the tree, which
floats higher than any other and reaches y=320. An earlier `y=340` clipped that
badge to 40x40, costing it a fifth of its pixels and nearly dropping it under
the height floor.

The bottom edge stops short of the chat and navigation bars.

## Calibration warning

Do not calibrate against the bundled `claim.png` and `claimPin.png` templates.
They are cut at a smaller scale than the game renders and measure 22-24 x 33, so
a window derived from them finds nothing on a real frame - which is exactly what
happened on the first attempt here.

## Counting

Claim counts are real counts. Each pass rescans, and a badge found again within
`SAME_BADGE_RADIUS` of one already tapped is logged as a warning rather than
counted a second time, so a screen where claiming silently fails reports zero
instead of a full total.

## Evidence

Saved real-frame verification, automated tests, and live account-log
confirmation. On 2026-08-23 the first run of this code claimed three badges -
two stations and the tree - and the account's essence counter moved from 42,955
to 44,012 in that run. The three preceding runs of the fixed-tap version had
reported "Claimed: 3" each while leaving the counter unchanged.

Unsupported: badges outside the search window, resolutions other than 720x1280,
and any island layout where two badges overlap closely enough to merge into one
blob.
