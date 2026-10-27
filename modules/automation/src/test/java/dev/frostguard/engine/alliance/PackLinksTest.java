package dev.frostguard.engine.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.vision.ocr.TextLine;

class PackLinksTest {

    private static final Path PASS = Path.of("C:/Bearguard/data/alliance-spend/passes/2026-10-26T17-56-10");

    @Test
    void findsTheLinkLinesOnARealScreenAndNothingElse() throws Exception {
        assumeTrue(Files.exists(PASS.resolve("007-unaligned.png")), "live pass frames not on this machine");
        BufferedImage screen = ImageIO.read(PASS.resolve("007-unaligned.png").toFile());
        List<PackLinks.Target> targets = PackLinks.find(screen);
        // A cut-off "Deals" at the top, "Daily Deals" twice (each wrapped over two lines), "Common Mark of Valor Pack".
        assertTrue(targets.stream().anyMatch(t -> Math.abs(t.y() - 1010) < 10 && t.x() > 300 && t.x() < 400), targets.toString());
        for (PackLinks.Target t : targets) {
            assertTrue(t.x() >= AllianceGiftLedger.TEXT_LEFT && t.x() < AllianceGiftLedger.RIGHT_COLUMN, t.toString());
        }
        assertEquals(6, targets.size(), targets.toString());
    }

    @Test
    void picksTheLinkInsideACardOnly() {
        List<PackLinks.Target> targets = List.of(new PackLinks.Target(260, 796), new PackLinks.Target(340, 1012));
        assertEquals(796, PackLinks.inCard(targets, 735).y());
        assertNull(PackLinks.inCard(targets, 580));
    }

    @Test
    void readsEveryDollarPriceOnAPage() {
        List<TextLine> lines = List.of(new TextLine("Scarlet Count", 200, 300, 200, 30, 0.9f),
                new TextLine("$4.99", 300, 1000, 80, 30, 0.9f), new TextLine("US$ 19,99 1200%", 300, 1100, 80, 30, 0.9f));
        assertEquals(List.of(4.99, 19.99), PackLinks.prices(lines));
    }

    @Test
    void tellsTheGiftListHeaderFromAnotherScreen() throws Exception {
        assumeTrue(Files.exists(PASS.resolve("000.png")), "live pass frames not on this machine");
        BufferedImage top = ImageIO.read(PASS.resolve("000.png").toFile());
        BufferedImage scrolled = ImageIO.read(PASS.resolve("006.png").toFile());
        assertTrue(PackLinks.sameHeader(top, scrolled));
        assertFalse(PackLinks.sameHeader(top, new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void remembersAFollowedPackAndPricesIt(@TempDir Path dir) throws Exception {
        PackLinks links = new PackLinks(dir);
        assertFalse(links.followed("Scarlet Count"));
        links.record("Scarlet Count", new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB),
                List.of(new TextLine("$9.99", 300, 1000, 80, 30, 0.9f)), LocalDateTime.parse("2026-10-27T12:00"));
        assertTrue(new PackLinks(dir).followed("Scarlet  count"));
        assertTrue(Files.exists(dir.resolve("pack-links/scarletcount.png")));
        assertEquals(9.99, PackLinks.pricesByPack(dir).get("scarletcount").first());
    }
}
