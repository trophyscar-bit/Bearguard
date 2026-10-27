# Table of contents

1. Business reference: principles, rules, and objectives.
2. Recommended gameplay strategy: decisions, priorities, and special cases.
3. UI reference: screens, important areas, visual feedback, and ambiguities.
4. Target coverage: expected features, explicit limitations, and unsupported behavior.
5. Task design: navigation, states, steps, exits, and rescheduling.
6. Risk management: FMEA-like analysis, detection, impact, and safe recovery.
7. Observability and statistics: data, metrics, and limitations.
8. Implementation status and gaps: delivered features, evidence, and differences from the design.

Chapters 1 through 3 are the business reference, written and maintained by the author; the assistant may only comment on them. Chapters 4 through 7 are the design to evolve together. Chapter 8 describes the implementation from the available sources. The actual effects of taps—animation duration, visual state changes, and UI variants—must be confirmed by observation.

# Nomadic Merchant: principles, rules, and objectives

The activity is a shop that offers items bought with gems or natural resources (meat, wood, coal, and steel):

- VIP points (always bought with gems);
- natural resources;
- teleports;
- and so on.

The shop is a permanent activity and resets once at the reset time. If an offer is not suitable, the player can refresh the offers for free or by paying gems.

# Recommended gameplay strategy: decisions, priorities, and special cases

The goal is to take every offer bought with natural resources and every VIP offer.

The shop is considered "finished" when no VIP offer or natural-resource-priced offer remains, and offers can no longer be refreshed without paying gems.

# UI reference: screens, important areas, visual feedback, and ambiguities

There is one screen with seven clickable areas:

- a two-row grid containing three offer cards per row;
- the offer-refresh button.

The UI uses the project's standard portrait viewport: `720×1280`.

An offer is bought by tapping its price area. Tapping the item type opens its description instead. After a purchase, the card transitions to a random replacement in about 1.5 seconds.

The replacement card can be identical to the previous card. The algorithm must therefore not use visual equality between two cards to conclude that a claim succeeded or that the shop is exhausted.

The refresh button renews the offer grid:

- three actions are free between shop resets (`Free Refresh`, green button);
- subsequent actions cost gems (`Refresh`, yellow button).

# Target coverage: expected features, explicit limitations, and unsupported behavior

The task must apply the strategy above: claim eligible offers, buy VIP points when the user enables that option, and use the refresh while it is free.

Behavior outside that strategy, intentional gem purchases outside the strategy, and unconfirmed UI effects remain out of scope until an explicit decision is made.

# Task design: navigation, states, steps, exits, and rescheduling

## Navigation

The task must require the city (`HOME`) as its start location, navigate to the Nomadic Merchant tab, and verify that the expected screen was reached.

After the visit, the generic layer must return the application to an acceptable screen: city (`HOME`) or world (`WORLD`). If neither screen is recognized, it must attempt bounded recovery and report failure to the scheduler.

On a domain error, the task must capture, classify, and finalize its cycle, then let the generic layer perform that normalization. An ADB outage cannot guarantee immediate navigation.

## Task states

Progress must be kept in memory by the task instance between executions. It does not need to survive a bot restart. A separate cycle state describes the visit in progress and is reset at the beginning of each execution.

Rescheduling must use progress, actions dispatched during the cycle, and the result of the latest visit. One visit calls rescheduling and records statistics exactly once.

## Algorithm flow and state calculation

### Task state

The task keeps its progress toward its objective between executions while the bot process is running. It defines these states:

| State | Meaning | Next execution |
| --- | --- | --- |
| `INACTIVE` | State reserved for an unconfigured task. A configured routine does not enter it. | No execution. |
| `READY` | Initial state of a configured routine. | Normal execution. |
| `COMPLETED_SUCCESS_RESCHEDULED` | The shop is exhausted and at least one claim or Free Refresh was dispatched during the cycle. | Daily reset + one minute. |
| `COMPLETED_UNVERIFIED` | The shop is exhausted without an action dispatched during the cycle. | Daily reset + one minute. |
| `TIMEOUT_RETRY` | First, second, or third retryable exit: timeout, navigation, scan, or action. | Five minutes. |
| `PARTIAL_RESCHEDULED` | Fourth retryable exit after at least one action was dispatched since the cycle began. | Daily reset + one minute. |
| `FAILED_RESCHEDULED` | Fourth retryable exit without an action dispatched since the cycle began. | Daily reset + one minute. |

### Cycle steps

The cycle state is temporary: it describes the current visit and starts from the beginning on the next execution. The proposed guideline is:

The priority is strict: resource-priced offers, then a VIP offer when the option is enabled, then Free Refresh. Free Refresh is considered only when neither offer category is selected.

1. **Prepare the visit** — verify the start location and navigate to the Nomadic Merchant shop.
2. **Examine resource-priced offers** — scan the six price areas and identify an offer whose price contains no gem.
3. **Attempt a claim** — tap the selected offer's price, then record any visual confirmation without making it the sole condition for continuing the cycle.
4. **Examine the VIP offer** — when the user enables the option and no resource-priced offer is selected, search for the VIP icon and follow its purchase flow.
5. **Examine Free Refresh** — when no eligible offer is found, detect the free refresh, use it when available, then scan again.
6. **Choose the exit** — stop the visit when the shop is exhausted, an anomaly requires a retry, or the time limit is reached.
7. **Finalize** — record statistics, determine the progress state, and calculate exactly one next execution.

Each step must end in a controlled way so the task can choose an exit and its next execution.

### Strategic decisions

A non-VIP offer is detected in its fixed price area. Absence of the gem template makes the offer eligible. This strategy avoids detecting the resource type and directly identifies the area to tap. The offer icon must not be tapped because it opens help instead of buying the offer.

A VIP offer is detected by its icon, only when the user enables VIP purchases. The purchase then opens a dialog: opening it starts from the detected icon, then the purchase and confirmation controls use fixed coordinates. Visual confirmation must be explicitly treated as information distinct from the dispatched action.

# Risk management: FMEA-like analysis, detection, impact, and safe recovery

The identified fragile points are templates, the six fixed price areas for resource-priced offers, VIP-dialog coordinates, and the ADB interface to the emulator. This grid is used to decide safe behavior before or during implementation; implementation details are collected in chapter 8.

| Risk | Available signal | Intended safe behavior | Missing decision or information |
| --- | --- | --- | --- |
| Navigation to the shop is not confirmed | The expected screen is not recognized. | Do not buy anything, finalize the visit, and schedule a retry. | Verify which intermediate screens are acceptable. |
| ADB error during capture, scan, or tap | Emulator communication exception. | Finalize without a recovery action that depends on ADB, then let the scheduler handle the retry. | Immediate return to an acceptable screen is impossible without a connection. |
| Invalid scan result | The vision engine returns no usable result. | Retry once, then classify the state as unknown and leave the cycle in a controlled way. | Distinguish this from normal absence of detection. |
| Free Refresh tap not dispatched | The tap cannot be sent. | Retry once, then leave the cycle in a controlled way. | This is not a game refusal: no action was dispatched. |
| UI or template becomes incompatible | An expected control is absent or the result is inconsistent. | Do not infer success; produce diagnostics and schedule a bounded retry. | Absence can also mean that the shop is genuinely exhausted or was changed manually. |
| Gem symbol is not detected | A gem-priced offer appears eligible. | Accepted risk: the user must report an observed malfunction. | Game behavior after the tap is indeterminate; timeout is the most likely outcome. |
| Insufficient resources | To be defined. | Do not claim a collection or spend occurred. | Not detected or handled today; behavior remains to be confirmed. |
| Claim or purchase is unconfirmed | Expected visual feedback is absent. | Record the uncertainty; state and scheduling remain based on the dispatched action. | Confirmation is informative and does not prove the claim. |
| Cycle takes too long | The execution limit is reached. | End the visit cleanly and schedule a bounded retry. | Define the duration limit and retry budget. |

# Observability and statistics: data, metrics, and limitations

Statistics must distinguish confirmed actions, actions that were only dispatched, and failures. They are used to assess the task's actual coverage and diagnose ambiguous situations; they are not proof of the in-game result.

Expected metrics are:

- confirmed resource claims;
- unconfirmed resource claims;
- confirmed and unconfirmed VIP purchases;
- Free Refresh taps dispatched;
- failed activity actions;
- scheduled short retries, with their reasons.

Each visit must also produce a summary log containing the selected state and the next execution time. Diagnostic captures are reserved for exits that justify them and do not replace live-account confirmation.

# Implementation status and gaps: delivered features, evidence, and differences from the design

## Current capabilities

The bot claims offers considered eligible, can buy VIP points when the user enables the option, and uses the refresh button while it is free.

Resource-priced offers are searched in the six fixed price areas by the absence of the gem template. VIP is searched by its icon. Price taps are fixed per slot; the VIP purchase flow combines the detected icon with fixed dialog coordinates.

Visual confirmation of a resource claim or VIP purchase is currently informative. Free Refresh is counted as a dispatched tap, without dedicated visual confirmation.

When a Free Refresh tap cannot be dispatched, it is attempted a second time. Two dispatch failures end the visit with `no_collect_error`; this is not a game refusal.

An unconfirmed action is nevertheless an action dispatched. The following path can therefore, rarely, reach `COMPLETED_SUCCESS_RESCHEDULED`: a claim is tapped, its visual confirmation fails, and the shop is then considered exhausted. This state describes shop exhaustion according to current detections; it does not prove the preceding claim.

## Current gaps and limitations

The task already finalizes its statistics and rescheduling when an exception occurs, but then rethrows the exception. The normal return to the generic layer therefore does not happen and screen normalization is skipped. This gap must be corrected so treated domain errors allow the generic flow to try returning to `HOME` or `WORLD`.

A `null` scan result is tried a second time. If it is still `null`, the task throws `IllegalStateException`, currently classified as `no_collect_error`. An `isFound() == false` result is normal absence of detection, not this error case.

`navigation_error`, `adb_error`, `no_collect_error`, and `timeout` currently share a budget of four consecutive visits. The first three are rescheduled after five minutes; the fourth is deferred until after the daily reset, with `PARTIAL_RESCHEDULED` if an action was dispatched since the cycle began, otherwise `FAILED_RESCHEDULED`.

A diagnostic snapshot is taken on that fourth exit for `navigation_error` or `no_collect_error`. Timeouts and short retries do not produce one; an ADB error is logged with `snapshot=unavailable`.
