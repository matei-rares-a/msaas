package com.saas.org.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Decides whether uploaded bytes are an acceptable logo (REQ-PERS-03): a PNG or a JPEG, judged by what
 * the bytes are - the file name and the declared content type are never consulted, so an SVG or an HTML
 * page renamed to {@code logo.png} is rejected - within the size and pixel limits. The dimensions are
 * read from the image header before anything is decoded, so a small file declaring a gigantic canvas
 * cannot exhaust memory; the image is then decoded once to prove it is genuinely an image and not just a
 * valid header followed by something else.
 */
@Component
public class LogoValidator {

    public enum LogoType {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg");

        private final String extension;
        private final String contentType;

        LogoType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        public static LogoType fromExtension(String extension) {
            for (LogoType type : values()) {
                if (type.extension.equals(extension)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("unknown logo type: " + extension);
        }
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final long maxBytes;
    private final int maxWidth;
    private final int maxHeight;

    public LogoValidator(@Value("${saas.logo.max-bytes}") long maxBytes,
            @Value("${saas.logo.max-width}") int maxWidth, @Value("${saas.logo.max-height}") int maxHeight) {
        this.maxBytes = maxBytes;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** @return the detected type; throws {@link LogoTooLargeException} or {@link IllegalArgumentException} otherwise. */
    public LogoType validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("the logo file is empty");
        }
        if (content.length > maxBytes) {
            throw new LogoTooLargeException("the logo may be at most " + maxBytes + " bytes");
        }
        LogoType type = detectType(content);
        checkDimensionsAndDecode(content);
        return type;
    }

    private static LogoType detectType(byte[] content) {
        if (startsWith(content, PNG_MAGIC)) {
            return LogoType.PNG;
        }
        if (startsWith(content, JPEG_MAGIC)) {
            return LogoType.JPEG;
        }
        throw new IllegalArgumentException("the logo must be a PNG or JPEG image");
    }

    private void checkDimensionsAndDecode(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("the logo is not a readable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > maxWidth || height > maxHeight) {
                    throw new IllegalArgumentException(
                            "the logo may be at most " + maxWidth + "x" + maxHeight + " pixels, got " + width + "x" + height);
                }
                reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("the logo is not a valid image", e);
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
