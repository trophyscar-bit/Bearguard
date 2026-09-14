package dev.frostguard.engine.deals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
 * for good. Each icon is the tile's top 54 px -- the corner label and the artwork -- so the
 * quantity printed in the bottom-right corner never takes part in the match.</p>
 *
 * <p>Measured on nine saved 720 x 1280 frames: true tiles score 84.7-100 while the best wrong
 * icon at any position scores at most 72.7 (the 5min Speedup template against a 1h tile), so
 * {@link #MIN_SCORE} leaves roughly twelve points either side, and overlapping candidates are
 * resolved in favour of the higher score.</p>
 */
public final class DealItemLibrary {

    static final double MIN_SCORE = 80.0;
    /** Candidates overlapping more than this share of the smaller box are one tile. */
    private static final double MAX_OVERLAP = 0.3;

    public record Match(String key, int x, int y, int width, int height, double score) {
    }

    private record Icon(String key, byte[] png, int width, int height) {
    }

    private final List<Icon> icons;

    private DealItemLibrary(List<Icon> icons) {
        this.icons = icons;
    }

    public static DealItemLibrary load(Path dir) throws IOException {
        List<Icon> icons = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new DealItemLibrary(icons);
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().toLowerCase().endsWith(".png")).sorted().toList()) {
                byte[] png = Files.readAllBytes(file);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) {
                    throw new IOException("Not a readable PNG: " + file);
                }
                String name = file.getFileName().toString();
                icons.add(new Icon(name.substring(0, name.length() - 4).replace('_', ' '), png,
                        image.getWidth(), image.getHeight()));
            }
        }
        return new DealItemLibrary(icons);
    }

    public int size() {
        return icons.size();
    }

    /** Best location of every icon inside the region, keeping one icon per tile. */
    public List<Match> find(RawImageData capture, PointData topLeft, PointData bottomRight) {
        List<Match> candidates = new ArrayList<>();
        for (Icon icon : icons) {
            ImageSearchResultData result = OpenCvPatternLocator.matchFromRawTemplate(
                    capture, icon.png(), topLeft, bottomRight, MIN_SCORE);
            if (result.isFound()) {
                candidates.add(new Match(icon.key(), result.getX() - icon.width() / 2,
                        result.getY() - icon.height() / 2, icon.width(), icon.height(), result.getMatchScore()));
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
