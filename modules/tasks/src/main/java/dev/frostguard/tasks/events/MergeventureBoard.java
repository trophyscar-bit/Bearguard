package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.PointData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the Mergeventure chess board from a 720x1280 frame without knowing any piece by name.
 *
 * <p>The board is a fixed 7x7 grid. Each cell is classed as fog (white cloud), plain dirt (a
 * locked tile with nothing on it), empty, or a piece. A piece may sit on a dirt mound
 * ("buried"): it unlocks when an identical free piece is dragged onto it, and it cannot be
 * dragged itself. A crown in the lower-left corner marks a max-level piece, which is used by
 * tapping it rather than merging.
 *
 * <p>Two pieces are judged identical by comparing their foreground pixels, where foreground is
 * anything far from both checkerboard beiges. That makes the test independent of which square
 * colour each piece sits on: identical pieces score under 4, the closest different pair seen
 * (wheat Lv. 1 against Lv. 2) scores over 100. Buried pieces sit on a mound that breaks that
 * mask, so they are matched by sliding the free piece's foreground over the buried cell.
 */
final class MergeventureBoard {

    static final int COLS = 7;
    static final int ROWS = 7;
    private static final int X0 = 90;
    private static final int Y0 = 250;
    private static final int PITCH = 90;
    private static final int HALF = 34;
    private static final int SIDE = HALF * 2;

    private static final int[][] SQUARE_BEIGES = {{216, 203, 174}, {228, 218, 189}};
    private static final int[] FOG = {235, 237, 248};

    static final double SAME_PIECE = 25;
    static final double SAME_BURIED_PIECE = 66;

    enum Kind { EMPTY, FOG, DIRT, PIECE }

    /** One grid square. {@code rgb} and {@code distance} cover the 68x68 square around its centre. */
    record Cell(int col, int row, Kind kind, boolean buried, boolean crowned, int[] rgb, int[] distance) {

        boolean isFreePiece() {
            return kind == Kind.PIECE && !buried;
        }

        PointData center() {
            return MergeventureBoard.center(col, row);
        }

        @Override
        public String toString() {
            return "(" + col + "," + row + ")";
        }
    }

    /** A drag from one cell onto another. */
    record Move(int fromCol, int fromRow, int toCol, int toRow, boolean unbury) {

        static Move of(Cell from, Cell to, boolean unbury) {
            return new Move(from.col(), from.row(), to.col(), to.row(), unbury);
        }

        PointData from() {
            return center(fromCol, fromRow);
        }

        PointData to() {
            return center(toCol, toRow);
        }

        @Override
        public String toString() {
            return "(" + fromCol + "," + fromRow + ")->(" + toCol + "," + toRow + ")" + (unbury ? " unbury" : "");
        }
    }

    private MergeventureBoard() {
    }

    static PointData center(int col, int row) {
        return new PointData(X0 + col * PITCH, Y0 + row * PITCH);
    }

    // ------------------------------------------------------------------ screen checks

    /** The board screen: pale cyan back arrow top-left and the dark wood frame on both sides. */
    static boolean isBoardScreen(BufferedImage img) {
        return near(img, 45, 45, 203, 255, 255, 45)
                && near(img, 30, 600, 115, 56, 30, 35)
                && near(img, 690, 600, 131, 64, 35, 35);
    }

    /** The storage bubble's red count badge, shown while pieces wait in temporary storage. */
    static boolean hasStoredPieces(BufferedImage img) {
        return fraction(img, 385, 925, 425, 945, p -> p[0] > 190 && p[1] < 90 && p[2] < 90) > 0.3;
    }

    // ------------------------------------------------------------------ reading cells

    static List<Cell> read(BufferedImage img) {
        List<Cell> cells = new ArrayList<>(COLS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                cells.add(readCell(img, col, row));
            }
        }
        return cells;
    }

    static Cell readCell(BufferedImage img, int col, int row) {
        PointData c = center(col, row);
        int cx = c.getX();
        int cy = c.getY();
        int[] rgb = new int[SIDE * SIDE * 3];
        int[] distance = new int[SIDE * SIDE];
        int fog = 0;
        int foreground = 0;
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                int argb = img.getRGB(cx - HALF + x, cy - HALF + y);
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                int i = y * SIDE + x;
                rgb[i * 3] = r;
                rgb[i * 3 + 1] = g;
                rgb[i * 3 + 2] = b;
                int d = Integer.MAX_VALUE;
                for (int[] beige : SQUARE_BEIGES) {
                    d = Math.min(d, Math.abs(r - beige[0]) + Math.abs(g - beige[1]) + Math.abs(b - beige[2]));
                }
                distance[i] = d;
                if (Math.abs(r - FOG[0]) + Math.abs(g - FOG[1]) + Math.abs(b - FOG[2]) < 30) {
                    fog++;
                }
                if (d > 40) {
                    foreground++;
                }
            }
        }
        double area = SIDE * SIDE;
        Kind kind;
        if (fog / area > 0.4) {
            kind = Kind.FOG;
        } else if (foreground / area < 0.06) {
            kind = Kind.EMPTY;
        } else if (fraction(img, cx - 30, cy - 30, cx + 30, cy + 30, MergeventureBoard::isDirt) > 0.8) {
            kind = Kind.DIRT;
        } else {
            kind = Kind.PIECE;
        }
        boolean buried = kind == Kind.PIECE
                && fraction(img, cx - 42, cy + 24, cx + 42, cy + 42, p -> p[0] - p[2] > 55 && p[1] < 165) > 0.7;
        boolean crowned = kind == Kind.PIECE
                && fraction(img, cx - 42, cy + 15, cx - 12, cy + 42,
                        p -> p[0] > 235 && p[1] > 170 && p[1] < 245 && p[2] < 70) > 0.06;
        return new Cell(col, row, kind, buried, crowned, rgb, distance);
    }

    private static boolean isDirt(int[] p) {
        return p[0] - p[2] > 45 && p[0] > 140 && p[0] < 215 && p[1] > 95 && p[1] < 170;
    }

    static long count(List<Cell> cells, Kind kind) {
        return cells.stream().filter(c -> c.kind() == kind).count();
    }

    static List<Cell> crowned(List<Cell> cells) {
        return cells.stream().filter(Cell::crowned).toList();
    }

    // ------------------------------------------------------------------ matching

    /** Lower is more alike: mean colour difference over shared foreground plus a shape penalty. */
    static double difference(Cell a, Cell b) {
        int shared = 0;
        int either = 0;
        long colour = 0;
        for (int i = 0; i < SIDE * SIDE; i++) {
            boolean inA = a.distance()[i] > 70;
            boolean inB = b.distance()[i] > 70;
            if (inA || inB) {
                either++;
            }
            if (inA && inB) {
                shared++;
                colour += Math.abs(a.rgb()[i * 3] - b.rgb()[i * 3])
                        + Math.abs(a.rgb()[i * 3 + 1] - b.rgb()[i * 3 + 1])
                        + Math.abs(a.rgb()[i * 3 + 2] - b.rgb()[i * 3 + 2]);
            }
        }
        if (either == 0) {
            return 999;
        }
        double meanColour = shared == 0 ? 255 : (double) colour / shared;
        return meanColour + (1 - (double) shared / either) * 100;
    }

    /** How well a free piece's foreground lines up with a buried cell, searched over +-10 px. */
    static double buriedDifference(BufferedImage img, Cell free, Cell buried) {
        PointData c = buried.center();
        double best = 999;
        for (int dy = -10; dy <= 10; dy += 2) {
            for (int dx = -10; dx <= 10; dx += 2) {
                long colour = 0;
                int n = 0;
                for (int y = 0; y < SIDE; y++) {
                    for (int x = 0; x < SIDE; x++) {
                        int i = y * SIDE + x;
                        if (free.distance()[i] <= 70) {
                            continue;
                        }
                        int argb = img.getRGB(c.getX() - HALF + dx + x, c.getY() - HALF + dy + y);
                        colour += Math.abs(free.rgb()[i * 3] - ((argb >> 16) & 0xff))
                                + Math.abs(free.rgb()[i * 3 + 1] - ((argb >> 8) & 0xff))
                                + Math.abs(free.rgb()[i * 3 + 2] - (argb & 0xff));
                        n++;
                    }
                }
                if (n > 0) {
                    best = Math.min(best, (double) colour / n);
                }
            }
        }
        return best;
    }

    /** Pieces grouped by identity, in reading order. Crowned pieces are max level and left out. */
    static List<List<Cell>> groups(List<Cell> cells) {
        List<List<Cell>> groups = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.kind() != Kind.PIECE || cell.crowned()) {
                continue;
            }
            List<Cell> home = null;
            for (List<Cell> group : groups) {
                if (difference(group.get(0), cell) < SAME_PIECE) {
                    home = group;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                groups.add(home);
            }
            home.add(cell);
        }
        return groups;
    }

    /**
     * The next drag worth making: unburying a tile first, since that opens board space, then any
     * two identical free pieces. Moves in {@code refused} already had no effect and are skipped.
     */
    static Optional<Move> nextMove(BufferedImage img, List<Cell> cells, Set<Move> refused) {
        List<Cell> free = cells.stream().filter(c -> c.isFreePiece() && !c.crowned()).toList();
        for (Cell buried : cells) {
            if (!buried.buried()) {
                continue;
            }
            Cell best = null;
            double bestScore = SAME_BURIED_PIECE;
            for (Cell candidate : free) {
                if (refused.contains(Move.of(candidate, buried, true))) {
                    continue;
                }
                double score = buriedDifference(img, candidate, buried);
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null) {
                return Optional.of(Move.of(best, buried, true));
            }
        }
        for (List<Cell> group : groups(cells)) {
            List<Cell> movable = group.stream().filter(Cell::isFreePiece).toList();
            for (int i = 1; i < movable.size(); i++) {
                Move move = Move.of(movable.get(i), movable.get(0), false);
                if (!refused.contains(move)) {
                    return Optional.of(move);
                }
            }
        }
        return Optional.empty();
    }

    /** True when both cells of a move look the same before and after, i.e. the drag did nothing. */
    static boolean unchanged(BufferedImage before, BufferedImage after, Move move) {
        return sameSquare(before, after, move.from()) && sameSquare(before, after, move.to());
    }

    private static boolean sameSquare(BufferedImage a, BufferedImage b, PointData centre) {
        long diff = 0;
        int n = 0;
        for (int y = centre.getY() - 30; y < centre.getY() + 30; y++) {
            for (int x = centre.getX() - 30; x < centre.getX() + 30; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                diff += Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff))
                        + Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff))
                        + Math.abs((pa & 0xff) - (pb & 0xff));
                n++;
            }
        }
        return (double) diff / n / 3 < 4;
    }

    // ------------------------------------------------------------------ pixels

    private interface PixelTest {
        boolean test(int[] rgb);
    }

    private static double fraction(BufferedImage img, int x0, int y0, int x1, int y1, PixelTest test) {
        int hits = 0;
        int total = 0;
        int[] p = new int[3];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = img.getRGB(x, y);
                p[0] = (argb >> 16) & 0xff;
                p[1] = (argb >> 8) & 0xff;
                p[2] = argb & 0xff;
                if (test.test(p)) {
                    hits++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    private static boolean near(BufferedImage img, int x, int y, int r, int g, int b, int tolerance) {
        int argb = img.getRGB(x, y);
        return Math.abs(((argb >> 16) & 0xff) - r) + Math.abs(((argb >> 8) & 0xff) - g)
                + Math.abs((argb & 0xff) - b) <= tolerance;
    }
}
