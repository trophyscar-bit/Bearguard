# Chat capture: nightly reconcile

The ordinary chat pass keeps up: it scrolls back about seventy-five screens per
channel and stops reading at the first message the transcript already has. That
fills a gap only while the gap is shorter than one pass can reach. A bot that
was paused, or a closed app, leaves a hole the next pass reaches into and cannot
finish, and nothing recorded that it happened.

The reconcile is the same pass with three differences, run once a day.

- **Longer.** `CHAT_RECONCILE_MINUTES_INT` per channel (default 5) instead of
  150 s. Measured 2026-09-23: 75 screens per 150 s, so five minutes is about 150
  screens.
- **Reads everything.** It does not stop at the first known message. The newest
  history it reaches is the part already stored, and a hole behind a run of
  known messages is what it was sent to find. Cost: reading 150 screens runs
  after the device is released, on the reader thread, and can take many minutes.
- **Estimates times.** The game gives messages no time. `at` has always been
  when a pass read the message. A message recovered hours later would land in
  today's file, so `ChatTimeEstimator` spreads each unknown run evenly between
  the stored messages either side of it (the newest run is bounded by now, an
  oldest run is counted back 30 s per message, never past 48 h). Estimated lines
  carry `"est":true`. A stored time is when a pass read the message, so an
  estimate can run late by up to one pass interval.

## When it runs

There is no second timer. The first pass at or after `CHAT_RECONCILE_TIME_STRING`
(default 01:00) each day is the reconcile, decided per channel from
`passes.jsonl` (`ChatReconcileSchedule.due`). `nextRunAfter` only pulls an
ordinary pass forward to land on the slot. A bot that was stopped through the
slot therefore reconciles on its first pass after resuming.

## What it records

`passes.jsonl` beside the transcript: a `photographed` and a `read` line per
channel per pass, with `mode`, `screens`, `stored` and `bridged`. `bridged` is
whether the walk ended on messages it already had. A reconcile that did not is
logged as `GAP NOT BRIDGED` and is the honest answer that the hole reaches past
what the budget covers. The file is also the source for "when was the bot not
listening", which the transcript alone cannot say.

## Fragile assumptions

- Stored times are coarse, so a run of many recovered messages is spread evenly
  rather than by real rate. Good enough to put a message on the right day and
  roughly the right hour, not to order it against another channel.
- The dedupe window is widened to 30,000 signatures primed from four day files
  for a reconcile. A message older than that would be stored twice.
- Reads queue behind one thread with at most two pending. A reconcile's reads
  can outlast the next ordinary pass, which then drops its frames with the
  existing warning; the next pass re-covers them.
- The game client's own scrollback depth is unmeasured. A gap older than it
  cannot be recovered and shows up as `GAP NOT BRIDGED`.

## Evidence

Automated tests: `ChatTimeEstimatorTest`, `ChatReconcileScheduleTest`,
`ChatPassLogTest`, `ChatPassOrderTest`, and the new cases in
`ChatTranscriptStoreTest`. Saved-frame and live-log confirmation on a real
account is still owed.
