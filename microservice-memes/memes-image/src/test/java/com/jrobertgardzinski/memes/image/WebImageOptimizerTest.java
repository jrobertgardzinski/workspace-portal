package com.jrobertgardzinski.memes.image;

import com.jrobertgardzinski.memes.config.ImageLimits;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Image")
@Feature("Optimisation")
class WebImageOptimizerTest {

    private final WebImageOptimizer optimizer = new WebImageOptimizer(new ImageLimits(1024));

    @Test
    @DisplayName("turns a BMP into a PNG, unchanged when within the limit")
    void turns_a_bmp_into_a_png() throws Exception {
        OptimizedImage optimized = optimizer.optimize(image("bmp", 4, 3));

        assertEquals("png", optimized.format());
        assertEquals((byte) 0x89, optimized.data()[0]); // PNG magic
        BufferedImage back = read(optimized);
        assertEquals(4, back.getWidth());
        assertEquals(3, back.getHeight());
    }

    @Test
    @DisplayName("scales an over-sized image down so its longest side fits the limit")
    void scales_down_an_oversized_image() throws Exception {
        OptimizedImage optimized = optimizer.optimize(image("png", 2000, 1000));

        BufferedImage back = read(optimized);
        assertEquals(1024, back.getWidth());     // 2000 -> 1024
        assertEquals(512, back.getHeight());     // 1000 * (1024/2000) = 512
        assertTrue(back.getWidth() <= 1024 && back.getHeight() <= 1024);
    }

    @Test
    @DisplayName("the UPLOAD path keeps the alpha channel — a transparent PNG is not stored as black")
    void keeps_alpha_when_the_upload_is_scaled_down() throws Exception {
        // This is the path that loses DATA, not just looks: optimize() re-encodes the bytes at
        // upload and the service stores THAT result, so alpha dropped here is gone from the
        // gallery for good — there is no original left to re-derive it from.
        byte[] transparent = transparentQuadrantPng(1600, 1200);
        assertTrue(ImageIO.read(new ByteArrayInputStream(transparent)).getColorModel().hasAlpha(),
                "the fixture itself really carries an alpha channel — every other fixture here is TYPE_INT_RGB");

        OptimizedImage optimized = optimizer.optimize(transparent);

        BufferedImage back = read(optimized);
        assertEquals(1024, back.getWidth(), "the image really went through the scaler (1600 -> 1024)");
        assertTrue(back.getColorModel().hasAlpha(), "the STORED bytes still have an alpha channel");
        assertEquals(0, back.getRGB(2, 2) >>> 24,
                "the transparent corner is still transparent — TYPE_INT_RGB made it opaque black");
        assertEquals(255, back.getRGB(back.getWidth() - 1, back.getHeight() - 1) >>> 24,
                "and the opaque half stayed opaque");
    }

    @Test
    @DisplayName("a thumbnail of a transparent PNG stays transparent — the gallery tile is not a black square")
    void keeps_alpha_in_a_thumbnail() throws Exception {
        // the thumbnail path scales practically EVERY meme (the limit is 256 px), so this is the
        // one a user sees first: a transparent sticker turned into a black tile on the wall
        OptimizedImage thumbnail = optimizer.toPngWithin(transparentQuadrantPng(600, 400), 256);

        BufferedImage back = read(thumbnail);
        assertEquals(256, back.getWidth(), "the thumbnail really was scaled down (600 -> 256)");
        assertEquals(0, back.getRGB(2, 2) >>> 24, "the transparent corner is still transparent");
        assertEquals(255, back.getRGB(back.getWidth() - 1, back.getHeight() - 1) >>> 24,
                "and the opaque half stayed opaque");
    }

    @Test
    @DisplayName("an opaque source (JPEG) stays flattened to RGB — no alpha channel is invented for it")
    void keeps_flattening_sources_that_have_no_alpha() throws Exception {
        // the other half of the bargain: alpha is preserved where it EXISTS, not added everywhere.
        // A channel of nothing but 0xff would grow every stored JPEG-sourced PNG for no gain.
        OptimizedImage optimized = optimizer.optimize(image("jpg", 2000, 1000));

        BufferedImage back = read(optimized);
        assertEquals(1024, back.getWidth(), "it really went through the scaler");
        assertFalse(back.getColorModel().hasAlpha(), "an opaque photo needs no alpha channel");
        assertEquals(255, back.getRGB(0, 0) >>> 24, "and it is fully opaque, as a JPEG always is");
    }

    @Test
    @DisplayName("re-encoding strips embedded metadata — no EXIF (GPS, camera) survives an upload")
    void re_encoding_drops_exif_metadata() throws Exception {
        // a JPEG with an APP1 "Exif" segment carrying something nobody should publish by accident
        byte[] secret = "SecretGPSLocation 52.2297N 21.0122E".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] jpeg = image("jpg", 64, 64);
        byte[] payload = new byte[6 + secret.length];
        System.arraycopy("Exif\u0000\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, payload, 0, 6);
        System.arraycopy(secret, 0, payload, 6, secret.length);
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(jpeg, 0, 2);                       // SOI
        tagged.write(0xFF); tagged.write(0xE1);         // APP1 marker
        int length = payload.length + 2;
        tagged.write((length >> 8) & 0xFF); tagged.write(length & 0xFF);
        tagged.write(payload);
        tagged.write(jpeg, 2, jpeg.length - 2);         // the rest of the original file
        byte[] withExif = tagged.toByteArray();
        assertTrue(contains(withExif, secret), "the crafted upload really carries the metadata");

        OptimizedImage optimized = optimizer.optimize(withExif);

        assertFalse(contains(optimized.data(), secret),
                "the stored image must not leak what the camera wrote");
        assertEquals(64, read(optimized).getWidth(), "and it is still the same picture");
    }

    @Test
    @DisplayName("a decompression bomb — tiny file, huge declared dimensions — is refused before decoding")
    void rejects_a_decompression_bomb_before_decoding() {
        // a valid PNG signature + IHDR declaring 10000x10000: a handful of bytes on the wire, but
        // decoding it would allocate ~400 MB — the guard must read the header and refuse, not decode
        byte[] bomb = pngHeaderDeclaring(10_000, 10_000);
        assertTrue(bomb.length < 100, "the hostile upload really is tiny");

        // the dedicated type, not a bare IllegalArgumentException — the web boundary maps it to a
        // 400 whose body may echo this message verbatim
        InvalidImageException refused = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidImageException.class, () -> optimizer.optimize(bomb));

        assertTrue(refused.getMessage().contains("image dimensions too large"), refused.getMessage());
    }

    @Test
    @DisplayName("a bomb that stays WITHIN the side limit but declares 16-bit RGBA is refused on its byte cost")
    void rejects_a_bomb_that_hides_inside_the_side_limit() {
        // 8000x8000 passes the side check twice over: the comparison is '>' and this is exactly the
        // ceiling. At the 4 bytes per pixel the guard used to assume that is 244 MiB — survivable.
        // Declared as 16-bit RGBA it is 8 bytes per pixel, i.e. 488 MiB from a file of a few hundred
        // kB, and three decode permits would admit ~1.4 GiB against a ~1075 MiB heap. The side alone
        // cannot see the difference; only the byte budget can.
        byte[] bomb = pngHeaderDeclaring(8_000, 8_000, 16, 6);
        assertTrue(bomb.length < 100, "the hostile upload really is tiny");

        InvalidImageException refused = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidImageException.class, () -> optimizer.optimize(bomb));

        assertTrue(refused.getMessage().contains("too large to decode"), refused.getMessage());
        assertTrue(refused.getMessage().contains("488 MiB"),
                "the refusal states what the decode would have cost: " + refused.getMessage());
    }

    @Test
    @DisplayName("the cost of a pixel is read from the real layout: 3, 4 and 8 bytes, not a flat guess")
    void charges_each_layout_what_it_actually_costs() throws Exception {
        // This is the arithmetic the byte budget rests on, and the one the old guard got wrong by
        // assuming a flat 4 bytes for everything. Small images on purpose — what is under test is
        // the price per pixel, and that does not depend on how many of them there are.
        assertEquals(3, bytesPerPixelOf(image("png", 8, 8)), "8-bit RGB: one byte per channel");
        assertEquals(4, bytesPerPixelOf(transparentQuadrantPng(8, 8)), "8-bit RGBA: plus alpha");
        assertEquals(8, bytesPerPixelOf(sixteenBitRgbaPng(8, 8)),
                "16-bit RGBA: two bytes per channel — the case that used to be charged half price");
    }

    @Test
    @DisplayName("a header we cannot interrogate is charged the worst case, not waved through")
    void charges_the_worst_case_when_the_layout_cannot_be_read() {
        // A file that is a header and nothing else — no pixel data — makes ImageIO refuse to
        // describe the layout. The guard must then assume the most expensive pixel it could be
        // (16-bit RGBA, 8 bytes), because rounding DOWN here is precisely how a bomb gets in.
        // 8000x8000 is inside the side limit, so only the byte budget can turn this away.
        byte[] headerOnly = pngHeaderDeclaring(8_000, 8_000, 8, 2);

        InvalidImageException refused = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidImageException.class, () -> optimizer.optimize(headerOnly));

        assertTrue(refused.getMessage().contains("488 MiB"),
                "charged as 8 bytes per pixel despite declaring 8-bit: " + refused.getMessage());
    }

    /** What {@link WebImageOptimizer} will charge one pixel of this image, straight from its header. */
    private static int bytesPerPixelOf(byte[] input) throws Exception {
        try (javax.imageio.stream.ImageInputStream stream =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            javax.imageio.ImageReader reader = ImageIO.getImageReaders(stream).next();
            try {
                reader.setInput(stream, true, true);
                return WebImageOptimizer.bytesPerPixel(reader);
            } finally {
                reader.dispose();
            }
        }
    }

    /** A real 16-bit-per-channel RGBA PNG — the layout that costs 8 bytes a pixel once decoded. */
    private static byte[] sixteenBitRgbaPng(int width, int height) throws Exception {
        java.awt.image.ComponentColorModel model = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                true, false, java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_USHORT);
        BufferedImage image = new BufferedImage(
                model, model.createCompatibleWritableRaster(width, height), false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** PNG signature + a well-formed IHDR chunk (correct CRC) declaring the given dimensions. */
    private static byte[] pngHeaderDeclaring(int width, int height) {
        return pngHeaderDeclaring(width, height, 8, 2);     // 8-bit truecolour: 4 bytes per pixel
    }

    /**
     * The same, with the pixel layout spelled out. Bit depth and colour type are what decide the
     * COST of a decode — 16-bit RGBA (depth 16, type 6) lands in a DataBufferUShort with four
     * bands, so it costs twice per pixel what the truecolour default does.
     */
    private static byte[] pngHeaderDeclaring(int width, int height, int bitDepth, int colourType) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();     // type + payload, CRC-covered
        chunk.writeBytes("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        chunk.writeBytes(java.nio.ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) bitDepth)
                .put((byte) colourType)
                .put((byte) 0).put((byte) 0).put((byte) 0)   // compression, filter, interlace
                .array());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(chunk.toByteArray());
        png.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(13).array());     // IHDR payload length
        png.writeBytes(chunk.toByteArray());
        png.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return png.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * A PNG that actually HAS an alpha channel: opaque red, except the top-left quadrant, which is
     * fully transparent. A whole quadrant, not a single pixel, because the scaler interpolates —
     * one transparent pixel among opaque neighbours would average out to nearly opaque and the
     * assertion would say nothing. The corner sampled by the tests sits deep inside the quadrant.
     */
    private static byte[] transparentQuadrantPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean transparentQuadrant = x < width / 2 && y < height / 2;
                image.setRGB(x, y, transparentQuadrant ? 0x00000000 : 0xffff0000);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage read(OptimizedImage optimized) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(optimized.data()));
    }
}
