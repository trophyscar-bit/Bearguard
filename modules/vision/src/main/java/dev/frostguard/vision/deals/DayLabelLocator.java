package dev.frostguard.vision.deals;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * The "Day" headers of a day-by-day reward grid (Journey Treasures). Each cell of the grid is headed "Day N"
 * over one reward tile; the word is found as a picture, every place it stands.
 *
 * <p>Measured on the Journey Treasures frame of 2026-10-25: the fourteen whole headers matched at 90 to 100,
 * the highlighted Day 1 lowest, and nothing else on the frame reached 60.</p>
 */
public final class DayLabelLocator {

    private static final String TEMPLATE = "/deals/day-label.png";
    private static final double MIN_SCORE = 0.8;

    private DayLabelLocator() {
    }

    /** Headers found, top to bottom and left to right; each box is the word "Day" alone. */
    public static List<PriceButtonLocator.Box> locate(BufferedImage frame) {
        Mat template = Template.MAT;
        Mat image = toMat(frame);
        Mat scores = new Mat();
        Imgproc.matchTemplate(image, template, scores, Imgproc.TM_CCOEFF_NORMED);
        List<PriceButtonLocator.Box> found = new ArrayList<>();
        while (true) {
            Core.MinMaxLocResult best = Core.minMaxLoc(scores);
            if (best.maxVal < MIN_SCORE) {
                break;
            }
            int x = (int) best.maxLoc.x;
            int y = (int) best.maxLoc.y;
            found.add(new PriceButtonLocator.Box(x, y, template.cols(), template.rows()));
            // One header, one hit: the scores around it are cleared before the next is taken.
            Imgproc.rectangle(scores, new Point(x - template.cols(), y - template.rows()),
                    new Point(x + template.cols(), y + template.rows()), new Scalar(-1), -1);
        }
        image.release();
        scores.release();
        found.sort((a, b) -> Math.abs(a.y() - b.y()) > template.rows() / 2 ? Integer.compare(a.y(), b.y())
                : Integer.compare(a.x(), b.x()));
        return found;
    }

    private static Mat toMat(BufferedImage frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        byte[] bgr = new byte[width * height * 3];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getRGB(x, y);
                bgr[i++] = (byte) (rgb & 0xFF);
                bgr[i++] = (byte) ((rgb >> 8) & 0xFF);
                bgr[i++] = (byte) ((rgb >> 16) & 0xFF);
            }
        }
        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        mat.put(0, 0, bgr);
        return mat;
    }

    private static final class Template {
        private static final Mat MAT = load();

        private static Mat load() {
            try (InputStream in = DayLabelLocator.class.getResourceAsStream(TEMPLATE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath resource " + TEMPLATE);
                }
                return Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_COLOR);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read " + TEMPLATE, unreadable);
            }
        }
    }
}
