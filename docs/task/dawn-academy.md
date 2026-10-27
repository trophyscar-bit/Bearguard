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

Learning picks the shortest of 10m, 2h, 10h and 23h whose XP covers what the
bar still needs. Learning XP accrues at one point per second, so 2h gives
7,200. XP past the bar carries over: 7,800/3,600 became 4,200/7,200 after an
upgrade on 27 Oct 2026. The 3d and 5d options are locked and never picked.

The XP fraction is read beside the Learn template. The card moves 40px when
Upgrade Skill is shown, so the read is relative, not fixed. The task does not
start a skill whose XP read fails or whose bar is already full without an
upgrade (out of Books). It checks the duration shown on the Learn button
before confirming.

Finish (gems), the gem Trek Supply row, the Frontier Supply shop and the paid
Wayfarer column are never tapped.

Auto Trek runs with "Auto-manage options and mini-games" ticked. The task
refuses to start without that tick, so the game answers every encounter.
Progress is the supply counter at `(512,22)..(615,60)`, which is the same on
the landing and both routes. The run ends at 0 with Next Stop showing, or
after a 90s stall. A stall without Next Stop is skipped or tapped through.

White digits on the coloured XP bars, buttons and counters read cleanly only
with `OcrSettingsData.forWhiteTextOnDark()`. The landing's Frontier counter is
the exception. Its lone `0` reads as `8` with that preset, so it uses the
plain path.

Compass gifting goes to the configured Expert, Agnes by default. An Expert
takes gifts only once settled in the Academy (1,000 Affinity from treks).
Until then the task logs and keeps the Compasses. Fiery Hearts and Sails are
never given.

The next run is the earliest of: learning end + 1 min, next free supply
(08:00 / 20:00 UTC) + 1 min, and daily reset + 5 min. It is clamped to
5 min .. 12 h.

Evidence: saved-frame tests in `DawnAcademyFrameTest` (`dawnacademy/` frames
from 27 Oct 2026) cover every template and OCR region the task uses. Live runs
are not yet observed.
