package com.example.orders.storage;

import com.example.orders.exception.InvalidOrderException;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an uploaded photo into what the shop serves: a large picture and a thumbnail, both JPEG.
 *
 * The upload is decoded, never trusted: only real JPEG, PNG and WebP pictures pass (SVG can carry
 * scripts, so it is not accepted), and the size is checked before the picture is decoded, so a small
 * file claiming huge dimensions cannot exhaust the memory. Phone photos are turned upright by their
 * orientation tag, and re-encoding drops all metadata, including the GPS position a phone records.
 */
@Component
public class ImageProcessor {

    public static final int LARGE = 1200;
    public static final int THUMB = 400;
    static final int MIN_SIDE = 200;
    static final int MAX_SIDE = 10_000;
    static final long MAX_PIXELS = 30_000_000L;
    private static final Set<String> FORMATS = Set.of("jpeg", "png", "webp");

    public record Processed(byte[] large, byte[] thumb, int width, int height) {}

    public Processed process(byte[] upload) {
        String format = checkFormatAndSize(upload);
        BufferedImage upright;
        try {
            // Thumbnailator applies the EXIF orientation of JPEGs; scale 1.0 keeps the size
            upright = Thumbnails.of(new ByteArrayInputStream(upload)).scale(1.0).useExifOrientation(true).asBufferedImage();
        } catch (IOException | RuntimeException e) {
            throw new InvalidOrderException("The " + format.toUpperCase(Locale.ROOT) + " file could not be read; it may be damaged");
        }
        BufferedImage flat = onWhite(upright);
        byte[] large = jpeg(flat, LARGE);
        byte[] thumb = jpeg(flat, THUMB);
        int width = Math.min(flat.getWidth(), scaledWidth(flat, LARGE));
        int height = Math.min(flat.getHeight(), scaledHeight(flat, LARGE));
        return new Processed(large, thumb, width, height);
    }

    /** Side of a profile photo, in pixels. */
    public static final int AVATAR = 256;

    /**
     * A profile photo: the middle square of the picture, AVATAR pixels a side. Re-encoding drops the
     * metadata, so a phone's GPS position never reaches the server's storage.
     */
    public byte[] avatar(byte[] upload) {
        String format = checkFormatAndSize(upload);
        BufferedImage upright;
        try {
            upright = Thumbnails.of(new ByteArrayInputStream(upload)).scale(1.0).useExifOrientation(true).asBufferedImage();
        } catch (IOException | RuntimeException e) {
            throw new InvalidOrderException("The " + format.toUpperCase(Locale.ROOT) + " file could not be read; it may be damaged");
        }
        BufferedImage flat = onWhite(upright);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Thumbnails.of(flat).crop(net.coobird.thumbnailator.geometry.Positions.CENTER).size(AVATAR, AVATAR)
                    .outputFormat("jpg").outputQuality(0.85).toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode a photo", e);
        }
    }

    /** A promotion banner: 3 wide by 1 high. */
    public static final int BANNER_WIDTH = 1500;
    public static final int BANNER_HEIGHT = 500;

    /**
     * A shop banner: the middle 3:1 strip of the picture, BANNER_WIDTH × BANNER_HEIGHT pixels (a smaller
     * picture is enlarged to fill it). Metadata is dropped, as for every upload.
     */
    public byte[] banner(byte[] upload) {
        String format = checkFormatAndSize(upload);
        BufferedImage upright;
        try {
            upright = Thumbnails.of(new ByteArrayInputStream(upload)).scale(1.0).useExifOrientation(true).asBufferedImage();
        } catch (IOException | RuntimeException e) {
            throw new InvalidOrderException("The " + format.toUpperCase(Locale.ROOT) + " file could not be read; it may be damaged");
        }
        if (upright.getWidth() < upright.getHeight()) {
            throw new InvalidOrderException("A banner picture must be wider than it is tall; 1500 × 500 pixels fits best");
        }
        BufferedImage flat = onWhite(upright);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Thumbnails.of(flat).crop(net.coobird.thumbnailator.geometry.Positions.CENTER).size(BANNER_WIDTH, BANNER_HEIGHT)
                    .outputFormat("jpg").outputQuality(0.85).toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode a banner", e);
        }
    }

    /** Reads only the header: the format and the dimensions. */
    static String checkFormatAndSize(byte[] upload) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new InvalidOrderException("Upload a JPEG, PNG or WebP picture");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!FORMATS.contains(format)) {
                    throw new InvalidOrderException("Upload a JPEG, PNG or WebP picture, not " + format.toUpperCase(Locale.ROOT));
                }
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw new InvalidOrderException("The picture is too large (" + width + " × " + height
                            + " pixels); at most 30 megapixels and 10,000 pixels a side");
                }
                if (width < MIN_SIDE || height < MIN_SIDE) {
                    throw new InvalidOrderException("The picture is too small (" + width + " × " + height
                            + " pixels); at least " + MIN_SIDE + " pixels a side");
                }
                return format;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidOrderException("Upload a JPEG, PNG or WebP picture");
        }
    }

    /** JPEG has no transparency: transparent parts become white instead of black. */
    private static BufferedImage onWhite(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** Fits inside box × box, never enlarged. */
    private static byte[] jpeg(BufferedImage image, int box) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var builder = Thumbnails.of(image).outputFormat("jpg").outputQuality(0.85);
            if (image.getWidth() > box || image.getHeight() > box) {
                builder.size(box, box);
            } else {
                builder.scale(1.0);
            }
            builder.toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode a photo", e);
        }
    }

    private static int scaledWidth(BufferedImage image, int box) {
        double factor = Math.min(1.0, Math.min((double) box / image.getWidth(), (double) box / image.getHeight()));
        return (int) Math.round(image.getWidth() * factor);
    }

    private static int scaledHeight(BufferedImage image, int box) {
        double factor = Math.min(1.0, Math.min((double) box / image.getWidth(), (double) box / image.getHeight()));
        return (int) Math.round(image.getHeight() * factor);
    }
}
