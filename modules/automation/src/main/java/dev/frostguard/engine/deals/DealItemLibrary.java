package dev.frostguard.engine.deals;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Curated item-tile icons, one PNG per item, named after the item ({@code 1h_Speedup.png}).
 *
 * <p>Curated, never self-taught: a misread saved under its OCR'd name would be matched against
 * for good. Each icon is the top 54 px of a full-size 94 px tile -- the corner label and the
 * artwork -- so the quantity printed in the bottom-right corner never takes part in the match.</p>
 *
 * <p>Tiles are drawn at different sizes per layout. Measured from the spacing of neighbouring
 * tiles on the 2026-09-13 scan frames: 1.00 on Hall of Chiefs and Top Chief, about 0.89 on
 * Craftsman's Treasure and the Vault, 0.83 on Regular Pack cards, 0.73 on Speedy Development Pack
 * and about 0.67 on Daily Deals. Every icon is matched at each of those sizes and the best one wins,
 * and a match reports its size so the quantity band can be placed to scale.</p>
 *
 * <p>At full size, true tiles scored 84.7-100 while the best wrong icon at any position scored at
 * most 72.7, so {@link #MIN_SCORE} leaves roughly twelve points either side; overlapping candidates
 * are resolved in favour of the higher score.</p>
 */
public final class DealItemLibrary {

    static final double MIN_SCORE = 80.0;
    static final double[] TILE_SCALES = {1.0, 0.89, 0.83, 0.73, 0.67};
    /**
     * Tile sizes tried when measuring a frame. The fixed sizes above were measured on September frames;
     * the Fire Crystal Leap Pack tab of 2026-10-22 draws tiles between 0.73 and 0.83, where every icon
     * scored low, half fell under {@link #MIN_SCORE} and the quantity band was placed for the wrong size.
     */
    static final double SCALE_STEP = 0.025;
    private static final double SMALLEST_SCALE = 0.60;
    private static final double LARGEST_SCALE = 1.05;
    /** Icons common enough on paid packs to measure a frame's tile size from. */
    private static final List<String> SCALE_ANCHORS = List.of("Gems", "5min Speedup", "100 VIP XP", "Fire Crystal",
            "1h Speedup");
    /** Candidates overlapping more than this share of the smaller box are one tile. */
    private static final double MAX_OVERLAP = 0.3;
    /** Template matching is CPU-bound; a handful of threads covers the 110 searches without starving the app. */
    private static final ExecutorService SEARCHES = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "deal-icon-search");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param scale the tile size this match was found at, relative to a full 94 px tile
     * @param loose the icon is drawn without its tile, so its count is printed beside or under it
     */
    public record Match(String key, int x, int y, int width, int height, double score, double scale, boolean loose) {
    }

    private record Variant(String key, byte[] png, int width, int height, double scale, boolean loose) {
    }

    /** @param drawnAt the tile size the icon was cut at; 1.0 unless its file name says {@code @0.775} */
    private record Original(String key, BufferedImage image, double drawnAt, boolean loose) {
    }

    private final List<Variant> variants;
    private final int icons;
    private final List<Original> originals;
    private final Map<String, Variant> scaledCache = new ConcurrentHashMap<>();

    private DealItemLibrary(List<Variant> variants, int icons, List<Original> originals) {
        this.variants = variants;
        this.icons = icons;
        this.originals = originals;
    }

    public static DealItemLibrary load(Path dir) throws IOException {
        List<Variant> variants = new ArrayList<>();
        List<Original> originals = new ArrayList<>();
        int icons = 0;
        if (!Files.isDirectory(dir)) {
            return new DealItemLibrary(variants, icons, originals);
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().toLowerCase().endsWith(".png")).sorted().toList()) {
                byte[] png = Files.readAllBytes(file);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) {
                    throw new IOException("Not a readable PNG: " + file);
                }
                String name = file.getFileName().toString();
                String stem = name.substring(0, name.length() - 4).replace('_', ' ');
                // A second cut of an item, taken where tiles are drawn small: shrinking the full-size icon
                // blurs its corner label, and "1hr(s)" stopped matching at all on the Leap Pack tiles.
                int at = stem.lastIndexOf('@');
                String key = at < 0 ? stem : stem.substring(0, at);
                // "Fire Crystal~loose": the item drawn without its tile, as on Dream Traveler packs.
                boolean loose = key.contains("~");
                if (loose) {
                    key = key.substring(0, key.indexOf('~'));
                }
                double drawnAt = at < 0 ? 1.0 : Double.parseDouble(stem.substring(at + 1));
                icons++;
                originals.add(new Original(key, image, drawnAt, loose));
                for (double scale : TILE_SCALES) {
                    BufferedImage scaled = scale == drawnAt ? image : scale(image, scale / drawnAt);
                    byte[] bytes = scale == drawnAt ? png : encode(scaled);
                    variants.add(new Variant(key, bytes, scaled.getWidth(), scaled.getHeight(), scale, loose));
                }
            }
        }
        return new DealItemLibrary(variants, icons, originals);
    }

    public int size() {
        return icons;
    }

    /** Best location of every icon inside the region, at whichever tile size fits best, keeping one icon per tile. */
    private static ImageSearchResultData await(Future<ImageSearchResultData> search) {
        try {
            return search.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("Item icon search failed", failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Item icon search interrupted", interrupted);
        }
    }

    /**
     * The tile size this frame draws, measured by matching the commonest icons across a fine ladder of
     * sizes and keeping the size of the single best match. Empty when no anchor icon is on the frame.
     */
    public OptionalDouble estimateScale(RawImageData capture, PointData topLeft, PointData bottomRight) {
        List<Variant> ladder = new ArrayList<>();
        for (Original original : originals) {
            if (!SCALE_ANCHORS.contains(original.key()) || original.drawnAt() != 1.0) {
                continue;
            }
            for (double scale = SMALLEST_SCALE; scale <= LARGEST_SCALE + 1e-9; scale += SCALE_STEP) {
                ladder.add(variant(original, scale));
            }
        }
        List<Future<ImageSearchResultData>> searches = new ArrayList<>();
        for (Variant variant : ladder) {
            searches.add(SEARCHES.submit(() -> OpenCvPatternLocator.matchFromRawTemplate(
                    capture, variant.png(), topLeft, bottomRight, MIN_SCORE)));
        }
        double bestScore = 0;
        double bestScale = Double.NaN;
        for (int i = 0; i < ladder.size(); i++) {
            ImageSearchResultData result = await(searches.get(i));
            if (result.isFound() && result.getMatchScore() > bestScore) {
                bestScore = result.getMatchScore();
                bestScale = ladder.get(i).scale();
            }
        }
        return Double.isNaN(bestScale) ? OptionalDouble.empty() : OptionalDouble.of(bestScale);
    }

    /** Like {@link #find(RawImageData, PointData, PointData)}, but only at the given size and its neighbours. */
    public List<Match> find(RawImageData capture, PointData topLeft, PointData bottomRight, double scale) {
        // The fixed sizes stay in the search alongside the measured one. A frame can draw tiles at more
        // than one size: on the 2026-10-22 cards page the daily-reward tiles are smaller than the rest, and
        // matched at the measured size alone the Weekly Card's 6 daily crystals were lost. Overlapping
        // candidates resolve to the best score, so each tile keeps whichever size fits it.
        List<Variant> sized = new ArrayList<>(variants);
        for (Original original : originals) {
            for (double s = scale - SCALE_STEP; s <= scale + SCALE_STEP + 1e-9; s += SCALE_STEP) {
                sized.add(variant(original, s));
            }
        }
        return search(capture, topLeft, bottomRight, sized);
    }

    private Variant variant(Original original, double scale) {
        double rounded = Math.round(scale / SCALE_STEP) * SCALE_STEP;
        String cacheKey = original.key() + "@" + original.drawnAt() + "@" + Math.round(rounded * 1000);
        return scaledCache.computeIfAbsent(cacheKey, k -> {
            BufferedImage scaled = scale(original.image(), rounded / original.drawnAt());
            try {
                return new Variant(original.key(), encode(scaled), scaled.getWidth(), scaled.getHeight(), rounded,
                        original.loose());
            } catch (IOException unencodable) {
                throw new UncheckedIOException(unencodable);
            }
        });
    }

    public List<Match> find(RawImageData capture, PointData topLeft, PointData bottomRight) {
        return search(capture, topLeft, bottomRight, variants);
    }

    private static List<Match> search(RawImageData capture, PointData topLeft, PointData bottomRight,
            List<Variant> variants) {
        // One full-page search per icon and scale: 110 of them took 3.8 s of a 5.2 s page read in sequence
        // on 2026-09-18 frames. Each search is independent; results are gathered in variant order so ties
        // resolve exactly as a sequential pass would.
        List<Future<ImageSearchResultData>> searches = new ArrayList<>();
        for (Variant variant : variants) {
            searches.add(SEARCHES.submit(() -> OpenCvPatternLocator.matchFromRawTemplate(
                    capture, variant.png(), topLeft, bottomRight, MIN_SCORE)));
        }
        List<Match> candidates = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            ImageSearchResultData result = await(searches.get(i));
            if (result.isFound()) {
                candidates.add(new Match(variant.key(), result.getX() - variant.width() / 2,
                        result.getY() - variant.height() / 2, variant.width(), variant.height(),
                        result.getMatchScore(), variant.scale(), variant.loose()));
            }
        }
        candidates.sort(Comparator.comparingDouble(Match::score).reversed());
        List<Match> kept = new ArrayList<>();
        for (Match candidate : candidates) {
            if (kept.stream().noneMatch(other -> overlap(candidate, other) > MAX_OVERLAP)) {
                kept.add(candidate);
            }
        }
        return inReadingOrder(kept);
    }

    /**
     * Rows top to bottom, tiles left to right. Tiles in one row match a pixel or two apart vertically, so a
     * plain y-then-x sort can put a row's right tile before its left one.
     */
    private static List<Match> inReadingOrder(List<Match> matches) {
        List<Match> byTop = new ArrayList<>(matches);
        byTop.sort(Comparator.comparingInt(Match::y));
        List<Match> ordered = new ArrayList<>();
        List<Match> row = new ArrayList<>();
        for (Match match : byTop) {
            if (!row.isEmpty() && match.y() - row.get(0).y() > row.get(0).height() / 2) {
                row.sort(Comparator.comparingInt(Match::x));
                ordered.addAll(row);
                row.clear();
            }
            row.add(match);
        }
        row.sort(Comparator.comparingInt(Match::x));
        ordered.addAll(row);
        return ordered;
    }

    private static BufferedImage scale(BufferedImage image, double scale) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static double overlap(Match a, Match b) {
        int width = Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
        int height = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int smaller = Math.min(a.width() * a.height(), b.width() * b.height());
        return (double) (width * height) / smaller;
    }
}
