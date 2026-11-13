# Red-dot Sweep

Task 926 (`RED_DOT_SWEEP_ENABLED_BOOL`, off by default, Events category) walks the Events, Deals
and Gem Shop tabs that carry a red dot and taps the free claims no dedicated task covers. It runs
10 to 15 minutes after the 00:00 UTC reset and again at 11:15 local.

## Whitelist and safety

- Panels: Events, Deals, Gem Shop (cart icon). Nothing else is opened. Chief Gear is not on the
  list, and a page whose banner or tab label reads as "gear" is left untouched.
- Mail, the Storehouse online chest and the VIP chest are logged as owned by tasks 30, 32 and 5
  and never visited.
- A tab that belongs to an enabled dedicated task (Hall of Chiefs, Defeat Beasts, Brothers in
  Arms, Hero Rally, Lucky Chip, Dead Shot, Fortune Hut, Winter Siege) is skipped by its tab
  template.
- A tap needs two proofs: a green pill (37,183,86; at least 90x36 px, 2,500 px) and a claim
  template inside its own box (`EVENTS_CLAIM_BUTTON`, `EVENTS_HERO_RALLY_CLAIM_ALL`,
  `EVENTS_LUCKY_CHIP_FREE_BUTTON`). Orange price buttons, the blue Top Up Now, grey Claims and
  green item frames fail the first proof.
- A page label that cannot be read is treated like Chief Gear: no tap, snapshot saved.
- After every claim the panel header must still show; a stray popup gets one Back, and a panel
  that is still not back ends the visit as unknown. A claim whose button is still green after the
  tap is unknown, not claimed.
- A red dot that is not on a claim button is logged with one snapshot per sweep and never tapped.
  This is informational and does not retry.

## Tips switches

Some red dots come from a ticked "Tip" reminder checkbox, not from a claim. On each page the sweep looks
for a ticked box (a 23x17 px light-green tick, (115,236,89)) with the `TIPS_LABEL` template right beside
it, unticks only that box and checks the tick is gone. A tick without the label beside it is never
tapped. Found in the saved frames: only the Mergeventure Caravan's "Tip" box (ticked and unticked
frames). The other ticked boxes in the frame set are not Tips switches and are not on swept panels: the
Dawn Academy learn-time boxes and Auto Trek box, and the City sidebar's "Hide after mission
completion". Mergeventure itself is reached from the City icon, not Events, and task 919 already
unticks its Tip.

## Evidence (live 720x1280 frames, already committed for other tasks)

- Dot: 13 px disc of (255,30,31), 140-150 px. The "!" alert badges at the strip ends are 33 px and
  fail the size test. Selected tab = white pixel beside the dot.
- Ready Claim: 147x51 px, 5,729 green px; template score 90.9 on it, 79.4 on a grey Claim.
- `GEM_SHOP_HEADER` (new template, cut from the Daily Deals frame) scores 100 and 99.99 on the two
  Gem Shop frames and 32-35 on Events and Deals frames.

## Unverified

- The `TIPS_LABEL` template was cut from the Caravan's brown skin; a Tip box on another page skin may
  not match until a frame of it is added.
- Claim All and Free buttons other than Lucky Chip's have no template yet, so they are not tapped.
- OCR of the page label on live pages; the policy is tested, the OCR read is not.
- The reward popup after a claim, and the milestone-chest icons on event progress bars.
- Tab-strip rewind and walk on every panel, and the Gem Shop's own tab strip being the same strip
  as Deals.

## Schedule

`RedDotSweepPolicy.nextRun`: the earliest of reset + 10..15 min (last or next reset) and 11:15
local. An unknown visit retries in 30 minutes, twice at most.

The late event claims moved off noon to fixed slots after the reset (`PostResetClaimSlots`):
Hall of Chiefs +25, Defeat Beasts +32, Hero Rally +39, Lucky Chip +46, Brothers in Arms +53
minutes. Dead Shot and Endless Trial stay at +60.
