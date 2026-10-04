package dev.frostguard.api.deals;

/**
 * One reward line inside an offer.
 *
 * @param key      stable identity used to compare the same item across packs
 * @param quantity how many units the pack gives
 * @param source   {@code icon} for a matched item tile, {@code text} for a listed row
 */
public record DealItem(String key, long quantity, String source) {
}
