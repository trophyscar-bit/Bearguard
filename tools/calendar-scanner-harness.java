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

        // Description lines the card reader sometimes lands on: still refused.
        failures += expectNoWindow(parse, "A perilous trade route where the");
        failures += expectNoWindow(parse, "war controis the tundra.");
        failures += expectNoWindow(parse, "");
        failures += expectNoWindow(parse, "Trick or treat");

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
