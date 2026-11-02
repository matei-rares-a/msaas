package com.saas.org.service;

import com.saas.org.TestImages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What counts as an acceptable logo (REQ-PERS-03): decided by the bytes, within size and pixel limits. */
@Tag("REQ-PERS-03")
class LogoValidatorTest {

    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private final LogoValidator validator = new LogoValidator(MAX_BYTES, 1024, 1024);

    @Test
    void aValidPngIsAccepted() throws IOException {
        assertThat(validator.validate(TestImages.image("png", 64, 64))).isEqualTo(LogoValidator.LogoType.PNG);
    }

    @Test
    void aValidJpegIsAccepted() throws IOException {
        assertThat(validator.validate(TestImages.image("jpg", 64, 64))).isEqualTo(LogoValidator.LogoType.JPEG);
    }

    @Test
    void theMaximumDimensionsAreAccepted() throws IOException {
        assertThat(validator.validate(TestImages.image("png", 1024, 1024))).isEqualTo(LogoValidator.LogoType.PNG);
    }

    @Test
    void oneMorePixelInEitherDirectionIsRejected() throws IOException {
        assertThatThrownBy(() -> validator.validate(TestImages.image("png", 1025, 10))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1024x1024");
        assertThatThrownBy(() -> validator.validate(TestImages.image("png", 10, 1025))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(TestImages.image("jpg", 2000, 2000))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} is rejected whatever it is called")
    @ValueSource(strings = {
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
            "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
            "<html><body><script>alert(1)</script></body></html>",
            "GIF89a......",
            "just some text pretending to be a logo.png"})
    void textSvgHtmlAndOtherFormatsAreRejected(String content) {
        assertThatThrownBy(() -> validator.validate(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNG or JPEG");
    }

    @Test
    void aFileThatOnlyStartsWithTheMagicBytesIsRejected() {
        byte[] pngHeaderOnly = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8};
        byte[] jpegHeaderOnly = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

        assertThatThrownBy(() -> validator.validate(pngHeaderOnly)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(jpegHeaderOnly)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValidImageWithAScriptAppendedAfterTheDataStillCountsOnlyAsTheImageItIs() throws IOException {
        // the trailing bytes are never interpreted: the type comes from the header and the response is nosniff
        byte[] withTrailer = concat(TestImages.image("png", 32, 32), "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        assertThat(validator.validate(withTrailer)).isEqualTo(LogoValidator.LogoType.PNG);
    }

    @Test
    void aTruncatedImageIsRejected() throws IOException {
        byte[] png = TestImages.image("png", 200, 200);

        assertThatThrownBy(() -> validator.validate(Arrays.copyOf(png, png.length / 2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThatThrownBy(() -> validator.validate(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValidImageOverTheSizeLimitIsRejectedAsTooLarge() throws IOException {
        byte[] noise = TestImages.noisyPng(1000, 1000); // incompressible, so well over 2 MB while within the pixel limits

        assertThat(noise.length).isGreaterThan((int) MAX_BYTES);
        assertThatThrownBy(() -> validator.validate(noise)).isInstanceOf(LogoTooLargeException.class);
    }

    @Test
    void aFileExactlyAtTheSizeLimitIsNotTooLarge() throws IOException {
        byte[] png = TestImages.image("png", 32, 32);
        LogoValidator tight = new LogoValidator(png.length, 1024, 1024);

        assertThat(tight.validate(png)).isEqualTo(LogoValidator.LogoType.PNG);
        assertThatThrownBy(() -> new LogoValidator(png.length - 1, 1024, 1024).validate(png)).isInstanceOf(LogoTooLargeException.class);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
