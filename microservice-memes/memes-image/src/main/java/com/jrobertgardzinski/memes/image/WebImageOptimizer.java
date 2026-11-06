package com.jrobertgardzinski.memes.image;

import com.jrobertgardzinski.memes.config.ImageLimits;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Re-encodes any image ImageIO can read (BMP, JPEG, GIF, …) into a browser-friendly PNG, scaling it
 * down so its longest side fits a maximum dimension. PNG re-encoding also drops non-pixel metadata
 * such as EXIF. Heavier optimisation (WebP) can grow here later.
 */
public class WebImageOptimizer {

    private static final String TARGET_FORMAT = "png";

    // Decompression-bomb guard, part one: a cheap sanity cut on the declared side length. A tiny
    // file can declare huge dimensions (a 10000x10000 uniform PNG compresses to a few kB), and
    // ImageIO.read allocates the WHOLE surface before we get a chance to downscale.
    private static final int MAX_DECODE_DIMENSION = 8000;

    // Part two, and the one that actually bounds the heap. Guarding the SIDE alone is a guess about
    // cost, because cost is bytes, not pixels: a 16-bit RGBA PNG (a format ImageIO reads without
    // complaint) decodes into a DataBufferUShort with four bands, i.e. 8 bytes per pixel, not the 4
    // this guard used to assume. At 8000px per side that is 488 MiB from a 486 kB upload — and the
    // side check let it through, because it compares with '>' and 8000 is not above 8000. Three
    // decode permits then admitted ~1.4 GiB against a ~1075 MiB heap (70% of the 1536Mi container
    // limit), so ONE verified user uploading the same small file three times could exhaust it.
    // Bounding width*height*bytesPerPixel instead makes the arithmetic in k8s/base/memes.yaml
    // ("three worst-case decodes are ~768Mi of heap") true rather than aspirational.
    private static final long MAX_DECODE_BYTES = 256L * 1024 * 1024;

    // What a pixel costs when the header will not say: 16-bit RGBA, the worst ImageIO hands us.
    // Guessing LOW here would reopen exactly the hole this guard exists to close.
    private static final int WORST_CASE_BYTES_PER_PIXEL = 8;

    private final ImageLimits limits;

    public WebImageOptimizer(ImageLimits limits) {
        this.limits = limits;
    }

    /** Optimise to a stored meme, bounded by the configured {@link ImageLimits}. */
    public OptimizedImage optimize(byte[] input) {
        return toPngWithin(input, limits.maxDimension());
    }

    /** Re-encode to a PNG whose longest side is at most {@code maxDimension} (used for thumbnails too). */
    public OptimizedImage toPngWithin(byte[] input, int maxDimension) {
        try {
            rejectDeclaresTooBigToDecode(input, MAX_DECODE_DIMENSION, MAX_DECODE_BYTES);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
            if (image == null) {
                throw new InvalidImageException("unsupported or unreadable image");
            }
            BufferedImage bounded = downscaleWithin(image, maxDimension);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(bounded, TARGET_FORMAT, out)) {
                throw new IllegalStateException("no writer for " + TARGET_FORMAT);
            }
            return new OptimizedImage(out.toByteArray(), TARGET_FORMAT);
        } catch (IOException e) {
            // ImageIO's readers catch THROWABLE and rewrap it as an IIOException, which is an
            // IOException — so heap exhaustion during a decode arrives here indistinguishable from
            // a truncated file. Calling that "your bytes are corrupt" is both a lie to the uploader
            // (their file was fine) and a cover-up: a 400 leaves no ERROR line and no 5xx, so
            // neither the operator nor the Http5xxBurst rule ever learns the JVM ran out of memory.
            // Let the Error travel: it is a server fault and has to be visible as one.
            Error fatal = causeOfType(e, Error.class);
            if (fatal != null) {
                throw fatal;
            }
            // everything read here is the CALLER's bytes (both streams are in-memory), so an
            // IOException means their image died mid-parse — signal "bad input", not a raw
            // UncheckedIOException, which the web boundary rightly treats as a server fault
            throw new InvalidImageException("unreadable image: the bytes are truncated or corrupt", e);
        }
    }

    /**
     * Reads width, height AND pixel layout from the image HEADER (no pixel decoding, so it is cheap
     * regardless of what the file claims) and refuses anything whose decode would blow the heap.
     * Unreadable input falls through — {@code ImageIO.read} then produces the usual "unsupported"
     * rejection.
     */
    private static void rejectDeclaresTooBigToDecode(byte[] input, int maxDimension, long maxBytes)
            throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > maxDimension || height > maxDimension) {
                    throw new InvalidImageException("image dimensions too large: " + width + "x" + height
                            + " (max " + maxDimension + " per side)");
                }
                // long on purpose: 8000 * 8000 * 8 overflows a signed int and would come out NEGATIVE,
                // i.e. the single largest bomb we can receive would sail past a check written in ints
                long declaredBytes = (long) width * height * bytesPerPixel(reader);
                if (declaredBytes > maxBytes) {
                    throw new InvalidImageException("image too large to decode: " + width + "x" + height
                            + " needs " + (declaredBytes / (1024 * 1024)) + " MiB of heap (max "
                            + (maxBytes / (1024 * 1024)) + " MiB)");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * What one pixel of this image will cost once decoded, read from the header's declared layout.
     *
     * <p>Packed models (an 8-bit ARGB PNG arrives as int-packed) hold every band inside ONE data
     * element, so their cost is the element size alone; component models store one element per band
     * and multiply. Anything we cannot interrogate is charged the worst case rather than waved
     * through — this method exists to bound an attacker's allocation, so its errors must round up.
     */
    static int bytesPerPixel(ImageReader reader) {
        ImageTypeSpecifier type;
        try {
            type = reader.getRawImageType(0);
            if (type == null) {
                Iterator<ImageTypeSpecifier> candidates = reader.getImageTypes(0);
                type = candidates.hasNext() ? candidates.next() : null;
            }
        } catch (IOException | RuntimeException headerWontSay) {
            // getRawImageType throws for readers that cannot describe the layout without decoding
            return WORST_CASE_BYTES_PER_PIXEL;
        }
        if (type == null) {
            return WORST_CASE_BYTES_PER_PIXEL;
        }
        SampleModel model = type.getSampleModel();
        int bytesPerSample = DataBuffer.getDataTypeSize(model.getDataType()) / 8;
        int samplesPerPixel = model instanceof ComponentSampleModel ? model.getNumBands() : 1;
        return Math.max(1, bytesPerSample * samplesPerPixel);
    }

    /** The first cause of the given type, if this throwable is carrying one. */
    private static <T extends Throwable> T causeOfType(Throwable thrown, Class<T> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    private static BufferedImage downscaleWithin(BufferedImage image, int maxDimension) {
        int longestSide = Math.max(image.getWidth(), image.getHeight());
        if (longestSide <= maxDimension) {
            return image;
        }
        double scale = (double) maxDimension / longestSide;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        // ARGB when the source HAS an alpha channel, RGB when it has not. This buffer used to be
        // unconditionally TYPE_INT_RGB, which silently destroyed transparency: a transparent pixel
        // drawn onto a buffer with no alpha composites over its (black) background and comes out as
        // opaque black. The stakes are not cosmetic — optimize() re-encodes the bytes AT UPLOAD and
        // the service stores THAT result, so every transparent PNG longer than
        // memes.image.max-dimension lost its alpha channel PERMANENTLY in the store, not just on
        // its thumbnail. The output format is PNG, which carries alpha, so nothing is lost by
        // keeping it. Opaque sources (JPEG, BMP, plain RGB PNG) stay flattened to RGB: they have no
        // alpha to preserve, and an alpha channel of nothing but 0xff would only make the stored
        // PNG bigger.
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled = new BufferedImage(width, height, type);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }
}
