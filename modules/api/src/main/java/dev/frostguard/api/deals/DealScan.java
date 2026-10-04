package dev.frostguard.api.deals;

import java.util.List;

/**
 * Everything one scan run read.
 *
 * @param scannedAt ISO-8601 local date-time the run finished
 * @param offers    offers read, deduplicated within the run
 * @param problems  surfaces, tabs or reads that failed, so a partial scan never looks complete
 */
public record DealScan(String scannedAt, List<DealOffer> offers, List<String> problems) {

    public DealScan {
        offers = offers == null ? List.of() : List.copyOf(offers);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
