package dev.frostguard.api.deals;

import java.util.List;
import java.util.Locale;

/**
 * One offer as read from a single frame.
 *
 * @param surface   where it was found, e.g. {@code Gem Shop} or {@code Deals}
 * @param tab       the tab label, or the page title when the tab has no readable label
 * @param title     the pack's own title
 * @param priceUsd  parsed price, or {@code null} when the price was not readable or not shown
 * @param priceText the raw price or requirement text, kept so an unread price stays visible
 * @param remaining purchases left, or {@code null} when not shown
 * @param purchased the pack showed {@code Purchased} instead of a price
 * @param items     rewards that were read; may be incomplete, never guessed
 * @param frame     frame file name relative to the scan's frame directory
 */
public record DealOffer(String surface, String tab, String title, Double priceUsd, String priceText,
        Integer remaining, boolean purchased, List<DealItem> items, String frame) {

    public DealOffer {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** The same pack seen on another day: surface and title, regardless of price. */
    public String packKey() {
        return (surface + "|" + title).toLowerCase(Locale.ROOT).trim();
    }

    /** A pack that can be priced per item: it has a price, was not already bought, and items were read. */
    public boolean isPricedPack() {
        return priceUsd != null && priceUsd > 0 && !purchased && !items.isEmpty();
    }
}
