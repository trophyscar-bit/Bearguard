# Deals Scan

`bg_deals_telemetry` is a read-only custom task that runs once a day shortly after the
00:00 UTC reset. It never taps a price button; it only opens surfaces, taps tab labels in the
tab strip band, swipes, and presses back. Results go to `<workspace>/data/deals`:

- `scans/<date>.json`: every offer read that day, plus the problems that stopped a read;
- `scans/<date>/NNN.png`: the frames behind those reads, kept for seven days;
- `item-icons/<item>.png`: the curated item library, seeded from `examples/custom-tasks/deals`;
- `item-values.json`: optional operator dollars per unit, which anchor the fitted values.

The Deal Tracker page reads those files. Pack verdicts and the per-item price tracker share
one set of fitted item values (`DealPriceTracker`), so they never disagree about what an item
is worth.

## Surfaces

Measured on 720 x 1280 frames on 2026-09-13.

- Gem shop (cart/gem-bag shortcut) and Deals (red present): horizontal tab strip at y ~150.
  A tab holds one pack (no vertical scroll) or stacked pack cards that scroll vertically. The
  Deals strip also carries a `Top-up Gift` tab.
- Right-hand shortcut column on the city view: icons come and go with live events, so they
  are found by the label or countdown printed 28-42 px below each icon, not by icon pictures.
  Events and Deals are skipped; everything else is opened and read, as a tabbed panel when its
  strip shows at least two labels, otherwise as a pop-up.

## Layout variants

- Pack cards: title, orange price button, `Remaining: n`, a salmon badge with the Top-up Gift
  coin (top-up points, 500 per dollar on all ten measured packs), and a row of item tiles
  (icon, optional corner label such as `1 hr(s)`, quantity).
- A one-pack tab's tile row is wider than the card and scrolls sideways; its last visible tile
  is clipped, which the reader reports so the task swipes the row.
- The timed pack pop-up lists items as text rows (`5m Construction Speedup x288`).
- Top-up Gift has no price: its orange `TOP UP NOW` button is an action, and the points still
  needed are recorded instead.
- Purchased packs show `Purchased` instead of a price button.

## Detection evidence

- Price button: saturated orange component (hue 30-64 degrees, S >= 150, V >= 200), aspect
  1.8-4.5, area 8,000-40,000 px, fill >= 60%. Found all ten buttons on seven frames and
  nothing else. A button whose own words are letters is an action, not a price.
- Text is read twice. White glyphs (titles, prices, listed rows, tile quantities) come
  from a hard white mask (V >= 190, S <= 70); dark text (`Remaining`, `Purchased`, top-up
  requirements) from the plain frame. The engine's soft colour isolation read tile quantities
  wrongly (160 as 60, 2,500 as 25090); the hard mask read them correctly as a whole row.
- The largest price label ($4.99 on one-pack tabs) is not read by the full-frame pass; its
  button crop is normalised to 40 px high before a second read.
- Card titles on silver banners (`Brilliant Custom Chest`) cannot be separated by the mask;
  such an offer is titled page title plus price, so tiers never merge in history.
- The pink value badge (`1640%`) is rotated display type and is not read.
- The top-up points badge is not read: the full-frame pass misses it, a region crop picks up the
  panel background, and a crop limited to the badge body read 2 of 6 frames.
- Item icons are the tile's top 54 px (corner label and artwork, no quantity). True tiles score
  84.7-100; the best wrong icon at any position scores at most 72.7 (5min against 1h Speedup).
  The floor is 80 and overlapping matches keep the higher score. A tile whose icon is not in
  the library is not read; adding its PNG starts tracking it.

## Pricing model

Each pack's price is shared across its items in proportion to quantity times unit value. Unit
values are fitted over the whole history until the median pack containing each item costs what
its items are worth; operator values stay fixed. A pack scores its worth per dollar (1.0 is
ordinary). Gem prices divide an item's dollar price by the fitted dollar value of the `Gems` item;
without gems in the history they stay unknown. Unpriced, purchased and itemless offers never
become prices.

## End-of-list detection

Timers and animation change every frame, so byte equality never holds. The task compares the
content region below the countdowns (mean channel difference under 3). Tab-strip swipes move
about one 198 px tab; 500 px swipes skipped labels between frames.

## Unsupported

- Craftsman's Treasure grade tabs other than the one showing on open.
- Unlabelled shortcut icons, and items missing from the icon library.
