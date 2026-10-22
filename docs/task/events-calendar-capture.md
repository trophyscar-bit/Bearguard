# Events Calendar Capture

How `bg_calendar` turns the in-game **Events -> Calendar** Gantt chart into the rows the
Upcoming Events panel renders. Written for an agent picking this up cold.

## Where it lives

- Scanner: `examples/custom-tasks/bg_calendar.java`, its own custom task, entry point
  `maybeScanStateCalendar()`. Custom tasks are **compiled from source at app startup**
  (`custom-tasks/custom_tasks.json` points at the file), so a source edit needs only an app
  restart, never `fg-build.bat`. It used to ride bg_telemetry's hourly pass; bg_telemetry still
  owns the HUD, the Task List and the Fortress read, and no longer touches the chart.
- Storage: `EventScheduleService.recordWindow(key, label, activeNow, startAt, endAt)` ->
  `event_schedule_entry` in `frostguard.db`. Calendar keys are prefixed `STATE_GANTT_`, the Task
  List's are `TASKLIST_`.
- UI: `modules/desktop/.../panel/events/UpcomingEventsLayoutController.java` (Week view first, then
  List). It converts the stored UTC values to `EventScheduleClock.zone()` for display.
- Operator data, **not committed except the names file**:
  - `data/calendar-events.txt` - canonical event names, one per line, committed.
  - `data/calendar-icons/<Name_With_Underscores>.png` - learned icons, workspace data.

## Time model

Everything stored is **UTC**, because the game's calendar is UTC (day rolls at 00:00 UTC). A
whole-day window is stored `00:00`..`23:59`. The chart is dated from the game's own clock banner
(OCR of `(120,226)-(604,290)`, regex `([0-9]{4})[-/]([0-9]{2})[-/]([0-9]{2})`), never from the PC
clock - a drifting local clock would silently move every bar. Machine UTC is the fallback and says
so in the log.

## One pass, end to end

1. **Gate**: `BG_TELEMETRY_LAST_STATE_CALENDAR_SCAN_DATE_STRING` = `<UTC date>/<SCAN_FORMAT_VERSION>`
   (currently `v12`), written through `ConfigService.writeAccountSetting` - `profile.setConfig`
   alone only edits the in-memory descriptor, which is why for a long while the marker died with
   the app and every restart swept the chart again. Bump the version to force a re-read after a
   format change. The task also reschedules itself to just after the next 00:00 UTC rollover, so
   the schedule and the marker are two independent reasons it runs once a game day.
2. **Navigate**: `ensureCorrectScreenLocation(LaunchPoint.WORLD)` -> `HOME_EVENTS_BUTTON` ->
   `EVENTS_CALENDAR_TAB`, swiping the tab strip back up to `CALENDAR_TAB_SWIPE_ATTEMPTS` (4) times
   because the strip keeps its last scroll position and grows with the number of live events.
3. **Sweep**: read the screen, then swipe `(360,900) -> (360,650)` over 1500 ms, settle 1200 ms,
   read again, up to 20 steps. Stop when `chartFingerprint()` (a hash of the chart region only -
   the clock banner ticks, so a full-screen hash never repeats) matches the previous screen; that
   last screen is re-read with `finalScreen = true`. Steps overlap heavily on purpose: a faster
   scroll skipped middle rows.
4. **Rows**: for each y in `392..1200`, a row is a bar row when any of the 7 day columns
   (`GANTT_LEFT 12`..`GANTT_RIGHT 707`) is >60% non-background. Backgrounds `(172,225,231)` and the
   today wash `(213,195,168)`; a section header `(112,184,209)` makes the column return -1 so
   headers never open a band. Bands under `GANTT_MIN_BAR_HEIGHT` (18) are ignored.
5. **Dates**: first and last covered column -> `today + (column - todayColumn)` days, where
   `todayColumn` is the column whose fill is >25% today-wash.
6. **Name**, strongest evidence first (the label OCR alone is never a name - it produces
   "Whortress Battlessuy", "aBizarne Bazaar"):
   1. **Icon match** - OpenCV `TM_CCOEFF_NORMED` of each library PNG across the whole bar band.
      Needs score >=80 and >=15 over the runner-up. Measured separation: same icon 95-100, best
      wrong icon ~52. Two stages: curated icons first, this pass's unidentified identities only if
      no curated icon wins.
   2. **Name list** - fuzzy windowed Levenshtein of the label OCR against `calendar-events.txt`,
      >=65 with a 12 margin. On a hit, the icon is learned to disk **under the curated name**.
   3. **Tooltip card** - see below.
   A label shorter than 3 characters is discarded, not kept as a stub.
7. **Write**: rows are accumulated in memory and written **only when the pass completes**
   (`forgetAll(STATE_GANTT_)` then insert). A pass that aborts keeps the previous read - clearing
   first once left the calendar holding six bars of one screen.

Key format: `STATE_GANTT_<NAME>_<start>` for a named event; an unidentified bar is keyed by its
in-memory icon identity instead, because Wander Theater and Wanderer Missions share both dates
exactly and a date-only key made one silently overwrite the other.

## Tooltip cards (the part that makes names and edge dates correct)

Tapping a bar opens a card in place with the full name and exact window. Used for two cases:

- a bar nothing else could name ("Mia's...", "Stand...", or icon-only with no text at all);
- a **named** bar touching column 0 or column 6, whose dates the chart has cut to the visible week.
  King of Icefield runs 09/21-09/27 but a chart ending on the 21st draws one day, and once its icon
  was learned that clipped read overwrote the card's window.

Mechanics, all of them scars:

- **Find the card by colour, not position.** It opens above or below the bar and moves sideways with
  it. Fill is near-white (R>=232, G>=244, B>=246); the first row from `y=208` with >120 such pixels
  is its top. Start at 208, not at the chart top: the panel's own white band at rows 184-206 reads
  as a card, and starting at 392 misses a card that opened above the bar.
- **OCR boxes** relative to card left/right/top: title `(left+100, top+35)-(right-8, top+100)`,
  window line `(left+8, top+105)-(right-8, top+160)`. One box for both let the card's icon leak in
  as a stray first word.
- **Positive anchor**: the window line must match
  `([0-9]{4})-([0-9]{2})-([0-9]{2}) +([0-9]{1,2}):([0-9]{2}) *- *...` or the card is not trusted.
  `24:00` means end of that day and is stored as `23:59`.
- **Close by tapping the footer note at (360,1234)**, never the clock banner: a card for a bar high
  on the chart covers the banner, so that tap landed on the card and it stayed open. Never tap
  inside a card - some carry a **Go** button that acts on the event.
- If a card will not close after two tries, the pass **aborts** (screen state is unknown) and the
  previous calendar is kept. Do **not** re-add a "chart moved" check that compares fingerprints
  before/after a tap: animated bar art changes the hash on its own and that aborted a healthy pass.
- Budget `TOOLTIP_MAX_PER_PASS` = 24 taps; each name's window is cached per pass so overlapping
  screens reuse it instead of re-tapping.
- A tooltip name is added to `calendar-events.txt` and its icon learned to disk **and** to the
  in-memory library, so the next screen and the next pass resolve it without a tap.
- Icon-derived names lose characters a file name cannot hold, so they are mapped back to the list's
  spelling ("Mias_Fortune_Hut" -> "Mia's Fortune Hut").

## Chart furniture, not events

- On the final screen only a band clipped by the **footer** is allowed through
  (`bandBottom >= GANTT_CONTENT_BOTTOM - 1`). A band clipped by the day header was already read
  whole one screen earlier; letting it through recorded its half-icon as an unidentified event.
- A band with no icon, no name and **no card when tapped** is not recorded at all. Every real event
  bar has a card, so this is furniture. Fired twice live, correctly.

## Bear Traps come from a different screen

The Task List (header clock on the World map) supplies `Bear Hunt - Trap N`, Fortress and Stronghold
battles. Read **row by row**: rows are found by fill colour down `x=34` - `Today` heading
`(211,157,99)`, dated heading `(143,173,221)`, entry card `(168,188,227)` - and each row is OCR'd on
its own (title/subtitle left of x=550, time or "Ended" right of it). One OCR block over the whole
panel mixed the white bear icons into the text and dropped the date heading at random, which made
every entry undated and erased the traps. Rows cut by the panel edge wait for a screen that shows
them whole; scrolling stops when the panel stops moving; an overlap row already read is not carried
twice. The previous rows are replaced only when the new read has dated entries and **no** undated
ones.

## Verifying a change

Live evidence is the only evidence that counts here; saved frames catch geometry, not behaviour.

```bash
grep -a "bg_telemetry" /c/Bearguard/logs/frostguard.log | grep -a "Calendar:\|State calendar\|Task List:" | tail -40
```

```bash
python -c "import sqlite3,datetime; c=sqlite3.connect('file:C:/Bearguard/frostguard.db?mode=ro',uri=True); [print(l,a,e) for l,a,e in c.execute(\"select event_label,active_since,inactive_since from event_schedule_entry where event_key like 'STATE_GANTT%' order by active_since\")]"
```

`active_since`/`inactive_since` are epoch millis of the stored `LocalDateTime` read in local time,
so a value printed as 03:59 EDT means the stored UTC value 23:59. A healthy pass logs
`State calendar: read N screen(s)` then `Calendar: stored M event(s)` with no `Unidentified`.

The task runs once a game day, scheduled for 00:12 UTC. A restart re-enqueues it but the marker
makes it skip; editing `firstExecutionUtc` in `custom_tasks.json` does **not** trigger a run.

## Operating rules that apply to this work

- Claim `prod-lock.ps1` before stopping the app; `node backup-settings.js` before every restart;
  stop with `stop-bearguard.ps1` (never `taskkill` - the SQLite WAL).
- Drive the game through `tools/adb/adb.exe` (device `127.0.0.1:16384`, 720x1280) only. Never move
  the operator's mouse or keyboard. In Git Bash, `export MSYS_NO_PATHCONV=1` for `/sdcard/...`.
- Never commit `data/game-analytics/` or anything carrying player names or account IDs.
- If a threshold needs tuning a second time, the approach is wrong - fix the template, the screen
  assumption, or the route instead.
