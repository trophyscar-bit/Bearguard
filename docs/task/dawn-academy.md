# Dawn Academy

One task (`DAWN_ACADEMY`, id 918, City → Dawn Academy) covers the Fire Crystal
Age Experts building: the learning queue, skill upgrades, Compass gifts, Trek
Supplies, Tundra and Frontier Auto Trek, and the free Endless Wayfarer track.
It replaces the older Expert Skill Training and Tundra Trek toggles, whose UI
moved into this panel. The skill priority list is the existing
`EXPERT_SKILL_TRAINING_PRIORITIES_STRING`.

Entry is the City sidebar Expert row (the book icon). Its second line is the
learning timer, read with the plain OCR path relative to the icon. The row's
arrow opens the learning Expert's skill card while a skill is learning. When
idle, the row reads "Learn Skills / Idle" and the arrow opens Dawn Academy
with a guide hand pointing at an Expert in their room. The Academy's Learn
icon only re-shows that hand. The task taps the fingertip, offset (-20,-5)
from the hand template, to open the Expert.

The queue is never left idle. The task notes which Experts live in the Academy
by stepping through them with the arrow. It then walks the configured skill
priorities, followed by every other slot of every Expert. The first unlocked
skill whose bar still needs XP is learned. If every unlocked bar is already
full and waiting on Books of Knowledge, the first of those is learned anyway
for 2h to bank XP that carries over to the next level ("Banking XP on ..." in
the log). On 29 Oct 2026 the old rule only looked at the first Expert shown,
and with Cyrille's two open skills full, the queue sat idle for 6h at a time.
When the idle Academy shows no guide hand, the task taps the Expert's spot in
the training room, `(395,850)`.

Learning picks the shortest of 10m, 2h, 10h and 23h whose XP covers what the
bar still needs. Learning XP accrues at one point per second, so 2h gives
7,200. XP past the bar carries over: 7,800/3,600 became 4,200/7,200 after an
upgrade on 27 Oct 2026. The 3d and 5d options are locked and never picked.

The XP fraction is read beside the Learn template. The card moves 40px when
Upgrade Skill is shown, so the read is relative, not fixed. The task does not
start a skill whose XP read fails or whose bar is already full without an
upgrade (out of Books), except to bank XP as above. It checks the duration shown on the Learn button
before confirming.

Gem Trek Supply buys are off by default (`DAWN_ACADEMY_BUY_SUPPLIES_BOOL`).
When ticked, the task uses every buy left today, 4 a day at 1,000 gems for 5
supplies, in the same supply-panel visit as the free claim. "Daily Limit: 1/4"
at `(500,696)..(662,724)` counts the buys left. At 0/4 the button becomes a
timer to the 00:00 UTC reset, so later passes that day read 0 and tap nothing.
Each buy opens the shared Purchase dialog ("Confirm to spend 1,000 Gems?"). Its
price is read, and the task closes the dialog without buying above 1,000 gems.
"Don't show this again today" is left unticked, so every buy shows its price.
The panel stays open after a buy, with no reward popup. The counter dropping
by one is the proof the buy went through; if it doesn't drop, the task stops.

Finish (gems), the Frontier Supply shop and the paid Wayfarer column are never
tapped.

Auto Trek runs with "Auto-manage options and mini-games" ticked. The task
refuses to start without that tick, so the game answers every encounter.
Progress is the supply counter at `(512,22)..(615,60)`, which is the same on
the landing and both routes. The run ends at 0 with Next Stop showing, or
after a 90s stall. A stall without Next Stop is skipped or tapped through.

White digits on the coloured XP bars, buttons and counters read cleanly only
with `OcrSettingsData.forWhiteTextOnDark()`. The landing's Frontier counter is
the exception. Its lone `0` reads as `8` with that preset, so it uses the
plain path.

The Academy building and the Expert's room show one of two speech bubbles.
A blue book means the learning queue is idle. A flame with a green up-arrow
means a Relationship Advancement is ready. While an advancement is pending,
the Expert's Stats tab shows "Relationship Advancement" where Gift normally
sits, so Compass gifting cannot run.

On 29 Oct 2026 that is why the log said "Gift button not found" for Agnes.
Agnes was at Lv. 10 Affinity 0/410 with 18 shards. Advance cost 5 and made
her Acquaintance 1 (+99,200 power, Earthbreaker Lv.2, new Efficient Recon),
and Gift came back. The flame then left the building and the book showed.

`DAWN_ACADEMY_ADVANCE_RELATIONSHIPS_BOOL` (on by default) runs before gifting.
It checks every Expert in the Academy and taps Advance only when the shard bar
at `(195,1098)..(520,1132)` reads have >= need. Then it dismisses
"Successfully Advanced" and repeats while further tiers are ready.

Compass gifting goes to the configured Expert, Agnes by default. An Expert
takes gifts only once settled in the Academy (1,000 Affinity from treks).
Until then the task logs and keeps the Compasses. Fiery Hearts and Sails are
never given.

The next run is the earliest of: learning end + 1 min, next free supply
(08:00 / 20:00 UTC) + 1 min, and daily reset + 5 min. It is clamped to
5 min .. 12 h.

Evidence: saved-frame tests in `DawnAcademyFrameTest` (`dawnacademy/` frames
from 27 Oct 2026, gem-buy frames from 28 Oct 2026) cover every template and OCR
region the task uses. The gem-buy flow (tap, confirm, counter 1/4 -> 0/4, gems
-1,000, supplies +5) was walked live by hand over ADB on 28 Oct 2026; the
task's own buy loop has not run live yet.
