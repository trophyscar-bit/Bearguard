# Stamina HUD

On the supported 720 × 1280 City and World views, Chief Stamina is shown as
white digits on a green HUD bar under the avatar. `updateStaminaFromHud` reads
it in place from `HUD_STAMINA_OCR_AREA` with white isolation and sends no taps:
every caller has already reached City or World. An unisolated read invented a
digit on a frame without the HUD, so the isolation is required.

Item top-up taps that bar to open the Obtain more dialog and reads the
`current/max` fraction from `STAMINA_OCR_AREA`. It closes the dialog with its
own close control, not Android Back: a Back that lands on the World map raises
the game's quit prompt. The wider `HUD_STAMINA_ENTRY` band, which also covers
the can, opens the same dialog.

After five rejected HUD reads stamina stays unknown and Frostguard captures the
current frame as `logs/snapshot/stamina/<utc>-ocr-failed.png`. The global
diagnostic-snapshot setting still controls this: when it is off, the warning
records `snapshot=disabled` and no frame is kept. The snapshot store validates
PNG conversion, retains the newest 20 stamina captures, and captures the
desktop only when its separate global setting is on.
