package dev.frostguard.engine.deals;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    /** Candidates overlapping more than this share of the smaller box are one tile. */
    private static final double MAX_OVERLAP = 0.3;

    /** @param scale the tile size this match was found at, relative to a full 94 px tile */
    public record Match(String key, int x, int y, int width, int height, double score, double scale) {
    }

    private record Variant(String key, byte[] png, int width, int height, double scale) {
    }

    private final List<Variant> variants;
    private final int icons;

    private DealItemLibrary(List<Variant> variants, int icons) {
        this.variants = variants;
        this.icons = icons;
    }

    public static DealItemLibrary load(Path dir) throws IOException {
        List<Variant> variants = new ArrayList<>();
        int icons = 0;
        if (!Files.isDirectory(dir)) {
            return new DealItemLibrary(variants, icons);
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().toLowerCase().endsWith(".png")).sorted().toList()) {
                byte[] png = Files.readAllBytes(file);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) {
                    throw new IOException("Not a readable PNG: " + file);
                }
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() - 4).replace('_', ' ');
                icons++;
                for (double scale : TILE_SCALES) {
                    BufferedImage scaled = scale == 1.0 ? image : scale(image, scale);
                    byte[] bytes = scale == 1.0 ? png : encode(scaled);
                    variants.add(new Variant(key, bytes, scaled.getWidth(), scaled.getHeight(), scale));
                }
            }
        }
        return new DealItemLibrary(variants, icons);
    }

    public int size() {
        return icons;
    }

    /** Best location of every icon inside the region, at whichever tile size fits best, keeping one icon per tile. */
    public List<Match> find(RawImageData capture, PointData topLeft, PointData bottomRight) {
        List<Match> candidates = new ArrayList<>();
        for (Variant variant : variants) {
            ImageSearchResultData result = OpenCvPatternLocator.matchFromRawTemplate(
                    capture, variant.png(), topLeft, bottomRight, MIN_SCORE);
            if (result.isFound()) {
                candidates.add(new Match(variant.key(), result.getX() - variant.width() / 2,
                        result.getY() - variant.height() / 2, variant.width(), variant.height(),
                        result.getMatchScore(), variant.scale()));
            }
        }
        candidates.sort(Comparator.comparingDouble(Match::score).reversed());
        List<Match> kept = new ArrayList<>();
        for (Match candidate : candidates) {
            if (kept.stream().noneMatch(other -> overlap(candidate, other) > MAX_OVERLAP)) {
                kept.add(candidate);
            }
        }
        kept.sort(Comparator.comparingInt(Match::y).thenComparingInt(Match::x));
        return kept;
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
