package com.saas.org;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/** Builds real PNG/JPEG bytes for logo tests, without binary fixtures in the repository. */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
            }
        }
        return write(image, format);
    }

    /** Incompressible content: a valid image that is nevertheless large in bytes. */
    public static byte[] noisyPng(int width, int height) throws IOException {
        Random random = new Random(42);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return write(image, "png");
    }

    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
