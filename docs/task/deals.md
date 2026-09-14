# Deals Scan

Read-only survey of every paid offer, meant to run once a day after reset. It never taps a
price button; the only interactions are opening a surface, switching tabs, swiping, and back.

## Surfaces

Measured on 720 x 1280 frames, 2026-09-13.

- Gem shop: the cart/gem-bag button at the top right of the HUD. Horizontal tab strip at
  y ~150 ending at `Tundra Supply Station`. A tab holds either one pack (no vertical scroll)
  or a stack of pack cards (Custom Chest tiers) that scrolls vertically.
- Deals: red present in the right-hand icon column. Tab strip ends at `Bank`; it also carries
  a `Top-up Gift` tab, so the separate Top-up Gift icon may be redundant.
- Timed single-pack pop-up (blue present with a countdown), Craftsman's Treasure, Top-up Gift:
  modal pop-ups closed with back. The right-hand column changes with live events, so its icons
  must be detected per run rather than assumed.

## Layout variants

- Pack cards: title, orange price button, `Remaining: n`, a gold badge beside the price, and a
  row of item tiles (icon, optional top label such as `1 hr(s)` or `10 K`, quantity).
- Item rows wider than the card scroll horizontally; the fifth tile is clipped.
- The timed pop-up lists items as text rows (`1h Construction Speedup x24`).
- Top-up Gift has no price: points required, points still needed, and a gem worth.
- Purchased packs show `Purchased` instead of a price button.

## Detection evidence

- Price button: saturated orange component (HSV hue 15-32, S >= 150, V >= 200), aspect
  1.8-4.5, area 8,000-40,000 px, fill >= 60%. White-text isolation then OCR with `$0-9.`
  read 10 of 10 buttons across seven frames ($4.99 through $99.99).
- Full-frame sparse OCR reads titles, `Remaining`, timers, day tabs, and pop-up text rows,
  but not prices or the value badge.
- The pink/red value badge (`1640%`) is rotated display type. Fixed-angle rotation and
  text-angle deskew both failed on all five samples; it is not read.
- Edge-detected item tiles are unreliable, and tile top labels and quantities misread the
  leading digit. Item identity needs a curated icon library, not OCR.

## End-of-list detection

Timers and animation change every frame, so byte equality never holds. Compare only the
scrolled content region, excluding countdowns. Tab-strip swipes of 500 px skipped tabs; move
roughly one tab width per gesture so every label is observed.
