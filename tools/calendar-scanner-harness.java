import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * Drives the real bg_calendar methods against the strings the live 11 Nov sweep actually OCR'd,
 * taken from C:\Bearguard-newui\logs\account_Default_1.log. The scanner is a custom task compiled
 * at runtime rather than a Maven module, so this is how its logic gets exercised offline.
 */
public final class GateHarness {

    public static void main(String[] args) throws Exception {
        Class<?> scanner = Class.forName("dev.frostguard.engine.listener.task.impl.bg_calendar");

        Method parse = scanner.getDeclaredMethod("parseTooltipWindow", String.class);
        parse.setAccessible(true);
        Method credible = scanner.getDeclaredMethod("credibleEventName", String.class);
        credible.setAccessible(true);

        int failures = 0;

        // Window lines, exactly as the live log recorded them.
        failures += expectWindow(parse, "2026-T1-09 00:00 - 2026-11-15 24:00",
                LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 15, 23, 59));
        failures += expectWindow(parse, "2026-T1-09 00:00 - 2026-T1-15 24:00",
                LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 15, 23, 59));
        failures += expectWindow(parse, "2026-T1-09 00:00 - 2026-T1-15 12:00",
                LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 15, 12, 0));
        failures += expectWindow(parse, "2026-11-18 00:00 - 2026-11-19 24:00",
                LocalDateTime.of(2026, 11, 18, 0, 0), LocalDateTime.of(2026, 11, 19, 23, 59));
        // Both letters of the pair misread together, from the 11 Nov 14:16 sweep.
        failures += expectWindow(parse, "2026-11-10 00:00 - 2026-TI-16 24:00",
                LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 16, 23, 59));
        failures += expectWindow(parse, "2026-TI-09 00:00 - 2026-TI-15 04:00",
                LocalDateTime.of(2026, 11, 9, 0, 0), LocalDateTime.of(2026, 11, 15, 4, 0));

        // Description lines the card reader sometimes lands on: still refused.
        failures += expectNoWindow(parse, "A perilous trade route where the");
        failures += expectNoWindow(parse, "war controis the tundra.");
        failures += expectNoWindow(parse, "");
        failures += expectNoWindow(parse, "Trick or treat");
        failures += expectNoWindow(parse, "Tundra Trade Route");
        failures += expectNoWindow(parse, "A perilous trade route where the TI tundra");

        // Names allowed into the operator's list, and the junk that started this.
        failures += expectName(credible, "Alliance Championship", true);
        failures += expectName(credible, "Mia's Fortune Hut", true);
        failures += expectName(credible, "Trick or treat", true);
        failures += expectName(credible, "Winter Siege", true);
        failures += expectName(credible, "er oe", false);
        failures += expectName(credible, "a8 Offic", false);
        failures += expectName(credible, "Arma y", false);
        failures += expectName(credible, "(nothing)", false);
        failures += expectName(credible, "oe", false);
        failures += expectName(credible, null, false);

        // bg_telemetry's Fortress stage pattern, against lines exactly as tesseract returned them
        // from the 12 Nov Alliance - Battle - Fortress - Signed up frames.
        Class<?> telemetry = Class.forName("dev.frostguard.engine.listener.task.impl.bg_telemetry");
        java.lang.reflect.Field field = telemetry.getDeclaredField("BATTLE_STAGE");
        field.setAccessible(true);
        java.util.regex.Pattern stage = (java.util.regex.Pattern) field.get(null);

        failures += expectStage(stage, "UTC 2026/11/13 3:00", "2026-11-13T03:00");
        failures += expectStage(stage, "X?200-7597) UTC 2026/11/13 9:00", "2026-11-13T09:00");
        failures += expectStage(stage, "9223Km away UTC 2026/11/13 14:00", "2026-11-13T14:00");
        failures += expectStage(stage, "(9529km away UTC 2026/11/13 17:00", "2026-11-13T17:00");
        failures += expectNoStage(stage, "Controlled by Phaethon Mercenaries");
        failures += expectNoStage(stage, "Stages already signed up for:");
        failures += expectNoStage(stage, "Reward expires in 02:14:51");

        // The card parser, against the raw text the live engine returned at 18:05 on 12 Nov. The
        // Stronghold card lost its 13:00 line to the OCR, which is the case the short-card warning is for.
        Method parseTimes = telemetry.getDeclaredMethod("parseBattleTimes", String.class, String.class);
        parseTimes.setAccessible(true);
        failures += expectTimes(parseTimes,
                " Sis& O-sign-up _) Controlled by Phaethon MercenariesEee Stages already signed up for:Se,"
                        + " UTC 2026/11/13 3:00X?400.Y:597 UTC 2026/11/13 9:00",
                "2026-11-13T03:00", "2026-11-13T09:00");
        failures += expectTimes(parseTimes,
                " sis,TAuto-sign-u ) Controlled by Phaethon MercenariesEg a Stages already signed up for:ML"
                        + " UTC 2026/11/13 9:00X:237eY:606 UTC 2026/11/13 13:009 223km away UTC 2026/11/13 14:00",
                "2026-11-13T09:00", "2026-11-13T13:00", "2026-11-13T14:00");
        // A repeat of the same line (a re-seen card after the scroll) is one candidate, not two.
        failures += expectTimes(parseTimes, "UTC 2026/11/13 9:00 UTC 2026/11/13 9:00", "2026-11-13T09:00");
        // A date that is not a date is dropped; the good one next to it is kept.
        failures += expectTimes(parseTimes, "UTC 2026/13/45 9:00 UTC 2026/11/13 13:00", "2026-11-13T13:00");
        failures += expectTimes(parseTimes, "Controlled by Phaethon Mercenaries");
        failures += expectTimes(parseTimes, "");

        System.out.println(failures == 0 ? "HARNESS PASS" : "HARNESS FAIL: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static int expectWindow(Method parse, String line, LocalDateTime from, LocalDateTime to)
            throws Exception {
        LocalDateTime[] window = (LocalDateTime[]) parse.invoke(null, line);
        if (window == null || !window[0].equals(from) || !window[1].equals(to)) {
            System.out.println("  FAIL window \"" + line + "\" -> "
                    + (window == null ? "null" : window[0] + " .. " + window[1]));
            return 1;
        }
        System.out.println("  ok window \"" + line + "\" -> " + window[0] + " .. " + window[1]);
        return 0;
    }

    private static int expectNoWindow(Method parse, String line) throws Exception {
        LocalDateTime[] window = (LocalDateTime[]) parse.invoke(null, line);
        if (window != null) {
            System.out.println("  FAIL expected no window from \"" + line + "\"");
            return 1;
        }
        System.out.println("  ok refused \"" + line + "\"");
        return 0;
    }

    private static int expectStage(java.util.regex.Pattern stage, String line, String expected) {
        java.util.regex.Matcher m = stage.matcher(line);
        if (!m.find()) {
            System.out.println("  FAIL stage not found in \"" + line + "\"");
            return 1;
        }
        String got = String.format("%s-%02d-%02dT%02d:%s", m.group(1), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5));
        if (!got.equals(expected)) {
            System.out.println("  FAIL stage \"" + line + "\" -> " + got + ", wanted " + expected);
            return 1;
        }
        System.out.println("  ok stage \"" + line + "\" -> " + got);
        return 0;
    }

    private static int expectTimes(Method parse, String card, String... expected) throws Exception {
        @SuppressWarnings("unchecked")
        java.util.List<LocalDateTime> got = (java.util.List<LocalDateTime>) parse.invoke(null, card, "card");
        java.util.List<String> gotText = new java.util.ArrayList<>();
        for (LocalDateTime time : got) {
            gotText.add(time.toString());
        }
        if (!gotText.equals(java.util.List.of(expected))) {
            System.out.println("  FAIL card times " + gotText + ", wanted " + java.util.List.of(expected));
            return 1;
        }
        System.out.println("  ok card times " + gotText);
        return 0;
    }

    private static int expectNoStage(java.util.regex.Pattern stage, String line) {
        if (stage.matcher(line).find()) {
            System.out.println("  FAIL expected no stage in \"" + line + "\"");
            return 1;
        }
        System.out.println("  ok no stage in \"" + line + "\"");
        return 0;
    }

    private static int expectName(Method credible, String name, boolean expected) throws Exception {
        boolean actual = (boolean) credible.invoke(null, name);
        if (actual != expected) {
            System.out.println("  FAIL name \"" + name + "\" -> " + actual + ", wanted " + expected);
            return 1;
        }
        System.out.println("  ok name \"" + name + "\" -> " + actual);
        return 0;
    }
}
