package dev.frostguard.api.deals;

import java.util.List;

/**
 * What a weekly or monthly card pays: {@code upfront} once on purchase ("Buy to get"), {@code daily} on every
 * day it stays valid ("Login daily to obtain"), for {@code validDays} days ("Valid: 7 day(s)").
 */
public record CardTerms(int validDays, List<DealItem> upfront, List<DealItem> daily) {

    public CardTerms {
        upfront = upfront == null ? List.of() : List.copyOf(upfront);
        daily = daily == null ? List.of() : List.copyOf(daily);
    }
}
