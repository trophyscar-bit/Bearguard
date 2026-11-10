# Dead Shot Sign-in

Task 922 (`DEAD_SHOT_SIGN_IN_ENABLED_BOOL`, off by default) claims the free daily tile in
Dead Shot's Sign-in Rewards. Dead Shot is a Deals tab; its page has a Sign-in Rewards button
on the left that opens a 12-day grid. The run is an hour after the 00:00 UTC reset.

## Flow and safety

City rail Deals button, `Deals` header check, Dead Shot tab, page title, Sign-in Rewards
button, popup title, glowing tile, popup X, Deals back arrow. The X shares its spot with the
page's `!` button, so it is tapped only while the popup title shows. Shoot!, x5, the ticket
`+` and History are never tapped.

The tab strip opens wherever it was left. The tab is looked for in place, then the strip is
walked toward its right end and back toward its start, one ~220 px swipe at a time; a
direction ends after two swipes that leave the strip still. No tab after both walks means the
event is not running, and the task waits for the next daily run.

## Detection evidence

Frames from 2026-11-10 (Day 2 claimable, then claimed):

- Templates (tab, selected tab, page title, Sign-in button, popup title, Deals header) were
  cut from these same frames; other screens in the set score 53 or less against them.
- The claimable tile's frame is (255,255,152); every other tile's frame is (254,210,152).
  Glow pixels are r >= 240, g >= 225, b 100-200. A tile is two facing vertical runs of at
  least 100 px joined by a horizontal run. The claimable frame has 132-151 px edges; the
  claimed frame has no run of 60 px.
- A claimed tile carries a green check: 631 pixels on Day 2 after the claim, none before.
  A claim counts only when the glow is gone and the check is there.
- The red dot over the Sign-in button (185 px at 96-110, 435-450) is only a cross-check: a
  dot with no glowing tile is an unknown outcome, not "nothing to claim".

## Unverified

- The glow pulses on other claim grids; only one phase was captured here, so the grid is
  read three times 500 ms apart.
- Tab-strip walking, the strip's still detection and the tab template on other days, other
  strip positions and other selected tabs.
- The double-width Day 7 and full-width Day 12 tiles glowing or claimed, and missed-day
  catch-up (more than one glowing tile).
- No reward pop-up appeared after the Day 2 claim; a pop-up that hides the popup title makes
  the claim unknown, which is retried in 30 minutes (four times at most per day).
- The Dead Shot page without its red dot.
