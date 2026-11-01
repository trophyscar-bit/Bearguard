package dev.frostguard.engine.squads;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.squads.SavedSquad;
import dev.frostguard.api.squads.TroopOverview;
import dev.frostguard.api.squads.TroopStock;
import dev.frostguard.vision.ocr.TextLine;

/** Reads Chief Profile > Troops Preview and the Squads (Deploy) screen it opens. */
public final class TroopsScreenReader {

    // Troop count pills on the Deploy screen, infantry / lancer / marksman.
    private static final int[] DEPLOY_COUNT_TOP = { 585, 725, 864 };
    private static final Pattern CAPACITY = Pattern.compile("\\d{1,3}(?:,\\d{3})*/\\d{1,3}(?:,\\d{3})*");

    private TroopsScreenReader() {
    }

    public static boolean isChiefProfile(RawImageData frame) {
        return SquadOcr.text(frame, 90, 18, 330, 64, SquadOcr.LINE).contains("Chief Profile");
    }

    /**
     * Centre of the "Amazing!" button on the "You're getting popular!" card that can cover the
     * Chief Profile when it opens, or null when that card is not showing.
     */
    public static PointData popularityCardButton(RawImageData frame) {
        for (TextLine line : SquadOcr.lines(frame, 150, 780, 570, 930)) {
            if (line.text().contains("Amazing")) {
                return new PointData(line.left() + line.width() / 2, line.top() + line.height() / 2);
            }
        }
        return null;
    }

    public static boolean isTroopsPreview(RawImageData frame) {
        return SquadOcr.text(frame, 95, 18, 360, 64, SquadOcr.LINE).contains("Troops Preview");
    }

    public static boolean isDeploy(RawImageData frame) {
        return SquadOcr.text(frame, 95, 18, 260, 64, SquadOcr.LINE).contains("Deploy");
    }

    /** Reads the "All" tab; returns null when the screen title is missing. */
    public static TroopOverview readPreview(RawImageData frame) {
        if (!isTroopsPreview(frame)) return null;
        String total = SquadOcr.text(frame, 70, 228, 225, 253, SquadOcr.LINE).replace(" ", "");
        String injured = SquadOcr.text(frame, 508, 228, 620, 253, SquadOcr.LINE).replace(" ", "");
        List<TroopStock> stocks = new ArrayList<>();
        stocks.addAll(column(SquadOcr.lines(frame, 150, 355, 370, 1130)));
        stocks.addAll(column(SquadOcr.lines(frame, 470, 355, 700, 1130)));
        return new TroopOverview(total.isEmpty() ? null : total, injured.isEmpty() ? null : injured, stocks);
    }

    /**
     * Reads the squad loaded on the Deploy screen. Hero names are filled in by the caller, which
     * owns the portrait gallery; this returns them as null placeholders.
     */
    public static SavedSquad readDeploy(RawImageData frame, int slot) {
        List<Integer> levels = new ArrayList<>();
        List<String> heroes = new ArrayList<>();
        for (HeroCards.Card card : HeroCards.deployCards()) {
            Integer level = HeroCards.level(frame, card);
            if (level != null) {
                levels.add(level);
                heroes.add(null);
            }
        }
        List<Long> troops = new ArrayList<>();
        for (int top : DEPLOY_COUNT_TOP) {
            troops.add(SquadOcr.number(SquadOcr.text(frame, 418, top, 530, top + 30, SquadOcr.WHITE_COUNT)));
        }
        Matcher m = CAPACITY.matcher(SquadOcr.text(frame, 84, 180, 272, 210, SquadOcr.CAPACITY));
        String capacity = m.find() ? m.group() : null;
        if (capacity != null) fillOneMissingCount(troops, SquadOcr.number(capacity));
        Long power = SquadOcr.number(SquadOcr.text(frame, 548, 180, 674, 210, SquadOcr.LINE));
        return new SavedSquad(slot, SavedSquad.State.SAVED, heroes, levels, troops, capacity, power);
    }

    // The capacity line's first number is the sum of the three counts, so one unread count (a
    // lone "0" pill does not OCR) is that sum minus the two that were read.
    static void fillOneMissingCount(List<Long> troops, Long used) {
        if (used == null || troops.stream().filter(Objects::isNull).count() != 1) return;
        long rest = troops.stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        if (used >= rest) troops.set(troops.indexOf(null), used - rest);
    }

    // Each troop line is a name such as "Apex Infantry" with its count on the line below.
    private static List<TroopStock> column(List<TextLine> lines) {
        List<TroopStock> stocks = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String name = lines.get(i).text().trim();
            String type = troopType(name);
            if (type == null) continue;
            TextLine next = lines.get(i + 1);
            Long count = SquadOcr.number(next.text());
            if (count != null && next.top() - lines.get(i).top() < 50) {
                stocks.add(new TroopStock(name, type, count));
                i++;
            }
        }
        return stocks;
    }

    private static String troopType(String name) {
        String[] words = name.split("\\s+");
        String last = words[words.length - 1];
        return HeroScreenReader.TROOP_CLASSES.contains(last) ? last : null;
    }
}
