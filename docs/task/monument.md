# Monument (Tundra Albums)

Live calibration of the Tundra Albums hub, captured against a real account at
720x1280 / 320dpi. Every coordinate and threshold below was measured from the
screen the routine actually lands on, not inferred from the layout.

## What a complete pass must do

Four independent items. A pass that stops after any one of them is a partial pass.

1. Claim the milestone chest in the top progress bar, when one is claimable.
2. Claim the album completion book, when one is claimable.
3. Empty the Fragment Backpack — every pack in every album slot.
4. Alliance Trade — claim any filled request, hold one open piece request, and
   send to allies while daily trades remain.

## Detection

The icon art is identical between claimable and non-claimable states, which is
why template matching on the icon has never worked here. The signal is always
the glow or the red badge, never the icon.

### Milestone chests

Six fixed slots at `x = 192, 277, 363, 459, 545, 635`, `y = 169`. The slots are
fixed; the milestone VALUES scroll through them as chests are claimed, so a slot
cannot be identified by its number.

Claimability is the sparkle rays radiating OUTSIDE the chest silhouette, sampled
as an annulus `r = 30..46` about the slot centre. A whole-box yellow ratio does
not work: the special gold-art chest is gold inside its own silhouette and scores
0.061 on a box test — a false positive — against 0.001 on the ring.

| state | ring ratio |
|---|---|
| claimable (sparkling) | 0.244 |
| already claimed (open lid) | 0.050 |
| locked, plain | 0.000 |
| locked, gold art | 0.001 |

Threshold: ring > 0.12.

### Album completion book

`x = 597`, album row pitch 333 (`y = 540`, `873`, ...). Keyed on the glow halo
around the book. The book animates, but its travel is only 11px (`y` centroid
540..551) against a ~60px target, so a tap at the mid-point cannot miss it.

| state | glow ratio |
|---|---|
| claimable (9/9) | 0.176 .. 0.241 across animation frames |
| not claimable (7/9) | 0.089, static |

Threshold: glow > 0.16.

### Bottom navigation badges

Both bottom-bar coordinates already in `MonumentRoutine` are correct — verified
by tapping them on the real hub.

| element | centre | badge region | badge ratio when set |
|---|---|---|---|
| Alliance Trade | (445, 1193) | (474, 1149)-(504, 1179) | 0.214 |
| Fragment Backpack | (635, 1193) | (644, 1146)-(690, 1180) | 0.202 |

Threshold: red > 0.08.

Both badges are truthful and both clear once their work is genuinely done
(0.202 -> 0.001 and 0.214 -> 0.000 respectively).

The Alliance Trade dot specifically means **an ally has filled your open request
and the piece is waiting to be claimed**. It does not track sends, and it does
not track whether a request is open — it stays lit until the Claim is taken, and
goes out the moment it is. It can light up at any time while the rest of the pass
is running, because it depends on an ally acting, so a pass that reads the dot
once at the start and never rechecks will miss a claim that arrived thirty
seconds later.

## Hub anchor

The routine must never tap the hub on the assumption it is there. The game's
modal scrim dims the whole frame, so the bottom nav bar's mean brightness
separates the hub from every other state.

| screen | nav-bar mean |
|---|---|
| albums hub | 141.0 |
| jigsaw assembly board | 120.2 |
| puzzle overview | 105.7 |
| fragment backpack | 60.0 |
| reward / bonus modal | 55 |
| pack detail | 24.9 |

Threshold: hub requires > 135. A value of 100 is too loose — the puzzle overview
and the assembly board both clear it while being nowhere near the hub.

## Screen flow and its traps

### The building opens a chooser, not the hub

Since at least 2026-10-28, tapping the Monument building raises two hexagon
options beside the tower: `Monument` (410, 808) and `Tundra Albums` (530, 808).
The hub only opens from the second. `MONUMENT_TUNDRA_ALBUMS_OPTION` scores
98.6-99.4 on the chooser and at most 45.5 on the city view and the hub, so it is
matched rather than tapped by coordinate.

### Chained reward modals

Claiming an album book emits **two** dialogs in sequence: `Rewards`, then
`Bonus Acquired`. A single "tap anywhere" dismissal leaves the second one up, and
every tap after that lands on the wrong screen. Dismissal must loop until the hub
anchor actually reads clear, never a fixed number of taps.

### Puzzle overview is not tap-anywhere dismissable

Exit is the X at (682, 40). Tapping low on this screen hits the scene selector
strip along the bottom, which merely re-selects scenes — the routine can sit
there indefinitely believing it is dismissing something.

### Two different "Assemble Now" buttons

Both exist and both are real:

- post-pack completion popup: (358, 810)
- puzzle overview: (556, 283) — matches the existing constant

"Assemble Now" carries a white sheen that sweeps across it every ~5s, so any
template or OCR match on this button must tolerate the sheen or sample across
frames.

The assemble confirm is the blue hexagon with a green check at the bottom right,
(672, 1193). Key it on the check's saturated green over
(630, 1155)-(715, 1235) — 0.061 when present, 0.000 on every other screen — not
on the button art.

### Fragment Backpack rows re-flow

Every album has a shelf. Owned packs sit as icons above the shelf banner with a
count box under them; an album with none shows a `Complete Album Missions ...
Obtain` panel instead. Rows shift as packs are consumed, and the list runs about
four screens, so packs are found by colour on every frame and the list is swiped
until a swipe no longer moves it (mean grey diff 31-43 while moving, 0.07 at the
end). Pack art is the only 80-200px blob of saturated gold/red/purple; banners and
planks are list-wide and Obtain is blue. Do not OCR the count: a lone `1` never
read, which left a Mythic General pack unopened on 2026-10-28.

Pack detail dialog geometry is stable across pack types: quantity defaults to the
full stack, `Enable` (358, 903). Anchor the tap on the Enable label itself
(250, 876)-(470, 932); the pack title wraps to two lines on themed packs
(`Explore the World - Mythic Scene Fragment Pack`) and does not OCR.

### Alliance Trade

The panel holds three independent jobs, and Claim is the one the red dot is
actually about.

**Claim** — when an ally has filled the open request, My Requests shows a green
`Claim` at (583, 368) in place of the Request button. Key it on the button's
saturated green over (515, 345)-(655, 392) — the existing `CLAIM_BTN_BOX`:
0.733 when a claim is waiting, 0.000 otherwise. Claim first, because claiming
frees the single request slot and lets a new request go out in the same pass.

- `Request` (358, 368), first-row ally `Send` (583, 710), `Owned: n` (520, 642)-(655, 678).
- Ally rows run highest Owned first (Owned 1 rows, then Owned 0 rows with a grey
  Send), so only the first row can hold a spare. Owned 1 is the only copy and is
  never sent; Owned 2+ is. Ordering is seen on one list with no 2+ row yet.
- Send buttons remain visible after the daily cap is reached. The stop condition
  is the `Trades Left Today (n/5)` counter (195, 505)-(575, 545), and a send only
  counts once it drops.
- Each send raises `Confirmation`: the piece with `Owned: n` again (105, 603)-(250, 642),
  blue `Confirm` (360, 760), and an X (662, 440) — no Cancel. The X closes it with
  nothing sent. Its "Don't show this again today" checkbox did not suppress later
  dialogs in testing, so it is handled every time.

### The piece picker is where the gem risk actually is

On the selected-piece card, `Obtain more` (178, 888) and `Request` (541, 888) sit
side by side at the same y. `Obtain more` is the gold-key purchase. An x-error of
360px spends gems. This screen — not the hub — is the origin of the purchase
dialog previously recorded in `ocr-debug`.

Only one piece request may be open at a time, so the pass order that actually
drains the panel is: claim -> request -> send. `Requests Left Today (n/3)` is a
daily quota spent per request; the one-open-at-a-time rule is separate from it. Requesting a second raises a
two-button `Confirmation` ("request X instead?") with Cancel (208, 788) and
Confirm (512, 788), whereas the first request raises a single-button `Tips`
dialog with Confirm at (358, 788). The existing single-coordinate confirm is a
no-op on the two-button dialog and lands close to Cancel. Before requesting, check
whether a piece already shows `Requesting...`; if one does, there is nothing to do.

Missing pieces render greyed out; owned pieces are saturated blue.

My Requests' `Request` (358, 368) does not open a piece card. It opens a scene's
piece grid, header `Complete this Scene to obtain.`, with an animated hand over a
missing piece; tapping a grey card opens the card above. The hand animates, so it
is never matched. Grid rows shift by scene (centres 410/590/778/965 in one,
450/635/820/1000 in another), so missing cards are found by scanning each column
(x = 162, 361, 560) for runs of grey strips with no saturated blue: missing rows
read blue 0.00, owned cards blue >= 0.36. Green `+n` tags on owned cards are the
spare duplicates that can be sent to allies. The grid closes with the X at (680, 40).

An open request shows `Requesting...` with an orange `Cancel` at (583, 367) and no
Request button. Read the state by colour, not OCR: the blue Request slab
(230, 340)-(490, 395) holds 11350 blue px when free and 0 when open; the Cancel slab
(515, 345)-(655, 392) holds 4464 orange px when open and 0 when free. An OCR box over
the label read `Requesting...` as `Request`. The game stays on the piece grid after
the Tips confirm; close it with its X.

Claiming a filled request shows a reward reveal and then a `Like and thanks`
card, each closed by a tap; re-read the trade title before every tap.
