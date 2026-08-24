package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.vision.ocr.PaddleOcrClient;
import dev.frostguard.vision.ocr.TextLine;

/** Proves the live service answers the same way the routine will ask it. */
public final class PaddleServiceCheck {
    private PaddleServiceCheck() {
    }

    public static void main(String[] args) throws Exception {
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        PaddleOcrClient client = new PaddleOcrClient("127.0.0.1", 6975);
        out.println("service up: " + client.isUp());
        BufferedImage img = ImageIO.read(new File(args[0]));
        for (int i = 1; i <= 5; i++) {
            long t0 = System.currentTimeMillis();
            List<TextLine> lines = client.read(img, 132, 250, 700, 1160, "en", 0.60);
            out.printf("call %d: %d lines in %d ms%n", i, lines.size(),
                    System.currentTimeMillis() - t0);
        }
    }
}
