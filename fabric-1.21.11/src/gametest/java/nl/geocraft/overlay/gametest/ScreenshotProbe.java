package nl.geocraft.overlay.gametest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Pixel-level checks on gametest screenshots. Everything is measured in the central
 * region of the image (the camera looks straight down on the overlay), so HUD remnants
 * at the edges never influence the result.
 */
final class ScreenshotProbe {

    private ScreenshotProbe() {}

    /** Fraction (0..1) of pixels in the central region for which the predicate holds. */
    static double centralFraction(Path png, PixelPredicate predicate) {
        BufferedImage img = read(png);
        int w = img.getWidth(), h = img.getHeight();
        int x0 = w / 4, x1 = w * 3 / 4, y0 = h / 4, y1 = h * 3 / 4;
        long hits = 0, total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (predicate.test((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255)) hits++;
                total++;
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    /** Fraction of pixels in the left (or right) half of the central region matching the predicate. */
    static double halfFraction(Path png, boolean left, PixelPredicate predicate) {
        BufferedImage img = read(png);
        int w = img.getWidth(), h = img.getHeight();
        int x0 = left ? w / 4 : w / 2, x1 = left ? w / 2 : w * 3 / 4, y0 = h / 4, y1 = h * 3 / 4;
        long hits = 0, total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (predicate.test((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255)) hits++;
                total++;
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    /** Fraction of central pixels that differ noticeably between two screenshots. */
    static double changedFraction(Path a, Path b) {
        BufferedImage ia = read(a), ib = read(b);
        int w = Math.min(ia.getWidth(), ib.getWidth()), h = Math.min(ia.getHeight(), ib.getHeight());
        int x0 = w / 4, x1 = w * 3 / 4, y0 = h / 4, y1 = h * 3 / 4;
        long hits = 0, total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = ia.getRGB(x, y), q = ib.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255))
                        + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 60) hits++;
                total++;
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    /** Magenta wool texture (~190,68,179) and a white carpet tinted (255,0,255) both satisfy this. */
    static boolean isMagenta(int r, int g, int b) {
        return r > 120 && b > 120 && g < 110 && r - g > 60 && b - g > 60;
    }

    @FunctionalInterface
    interface PixelPredicate {
        boolean test(int r, int g, int b);
    }

    private static BufferedImage read(Path png) {
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null) throw new AssertionError("Kon screenshot niet lezen: " + png);
            return img;
        } catch (IOException e) {
            throw new AssertionError("Kon screenshot niet lezen: " + png, e);
        }
    }
}
