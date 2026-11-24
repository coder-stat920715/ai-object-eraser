package com.example.objecteraser.service;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.exception.InvalidImageException;
import com.example.objecteraser.util.ImageUtils;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Validation, mask alignment, mask dilation and result compositing.
 */
@Service
public class ImageProcessingService {

    private static final Set<String> OUTPUT_FORMATS = Set.of("png", "jpeg");

    private final EraserProperties props;

    public ImageProcessingService(EraserProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ decoding / validation

    /** Validates and decodes the photo into an opaque RGB image. */
    public BufferedImage readImage(byte[] bytes) {
        return ImageUtils.toRgb(decode(bytes, "image"));
    }

    /**
     * Validates and decodes the mask, binarises it (0/255), forces it to the pixel size of the photo
     * and rejects masks without any painted pixel.
     */
    public BufferedImage readMask(byte[] bytes, int width, int height) {
        BufferedImage decoded = ImageUtils.toBinaryMask(decode(bytes, "mask"));
        BufferedImage aligned = alignMask(decoded, width, height);
        if (!ImageUtils.hasMarkedPixels(ImageUtils.maskBytes(aligned))) {
            throw new InvalidImageException("The mask is empty. Paint over the object you want to remove first.");
        }
        return aligned;
    }

    /** Resizes the mask (nearest neighbour, stays binary) when its size differs from the photo. */
    public BufferedImage alignMask(BufferedImage mask, int width, int height) {
        if (mask.getWidth() == width && mask.getHeight() == height) {
            return mask;
        }
        byte[] resized = ImageUtils.resizeMaskNearest(ImageUtils.maskBytes(mask),
                mask.getWidth(), mask.getHeight(), width, height);
        return ImageUtils.maskFromBytes(resized, width, height);
    }

    private BufferedImage decode(byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidImageException("The " + label + " file is empty.");
        }
        long max = props.limits().maxFileSize().toBytes();
        if (bytes.length > max) {
            throw new InvalidImageException("The " + label + " is larger than the allowed " + (max / (1024 * 1024)) + " MB.");
        }
        if (!looksLikeSupportedImage(bytes)) {
            throw new InvalidImageException("Unsupported " + label + " type. Use PNG, JPEG or WEBP.");
        }
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidImageException("The " + label + " could not be decoded as an image.");
            }
            reader = readers.next();
            reader.setInput(in, true, true);
            long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
            if (pixels > props.limits().maxPixels()) {
                throw new InvalidImageException("The " + label + " has " + pixels + " pixels; the limit is "
                        + props.limits().maxPixels() + ". Downscale it and try again.");
            }
            BufferedImage img = reader.read(0);
            if (img == null) {
                throw new InvalidImageException("The " + label + " could not be decoded as an image.");
            }
            return img;
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImageException iie) {
                throw iie;
            }
            throw new InvalidImageException("The " + label + " is corrupt or not a valid image.", e);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** Magic-number sniffing: PNG, JPEG, WEBP. The client-declared content type is never trusted. */
    static boolean looksLikeSupportedImage(byte[] b) {
        if (b.length < 12) {
            return false;
        }
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return png || jpeg || webp;
    }

    // ------------------------------------------------------------------ mask dilation

    public int resolveGrowMask(Integer requested) {
        int value = requested == null ? props.dilationRadius() : requested;
        if (value < 0 || value > 64) {
            throw new InvalidImageException("growMask must be between 0 and 64 pixels.");
        }
        return value;
    }

    /** Grows the white (erase) region by {@code radius} pixels with a disc structuring element. */
    public BufferedImage dilate(BufferedImage mask, int radius) {
        byte[] out = ImageUtils.dilate(ImageUtils.maskBytes(mask), mask.getWidth(), mask.getHeight(), radius);
        return ImageUtils.maskFromBytes(out, mask.getWidth(), mask.getHeight());
    }

    // ------------------------------------------------------------------ compositing / encoding

    /**
     * Blends the inpainted pixels into the original using a feathered version of the mask.
     * Pixels inside the mask are always taken from the inpainted image; the soft edge extends
     * outwards so there is no visible seam. Everything else stays byte-identical to the input.
     */
    public BufferedImage composite(BufferedImage original, BufferedImage inpainted, BufferedImage mask) {
        final int w = original.getWidth();
        final int h = original.getHeight();
        BufferedImage inp = (inpainted.getWidth() == w && inpainted.getHeight() == h)
                ? inpainted : ImageUtils.resizeSmooth(inpainted, w, h);
        final byte[] m = ImageUtils.maskBytes(mask);
        final int[] o = original.getRGB(0, 0, w, h, null, 0, w);
        final int[] p = inp.getRGB(0, 0, w, h, null, 0, w);

        final float[] alpha = new float[w * h];
        for (int i = 0; i < alpha.length; i++) {
            alpha[i] = m[i] != 0 ? 1f : 0f;
        }
        final float[] soft = ImageUtils.boxBlur(alpha, w, h, props.featherRadius());

        final int[] out = new int[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float a = m[i] != 0 ? 1f : Math.min(1f, soft[i] * 2f);
                if (a <= 0f) {
                    out[i] = o[i];
                } else if (a >= 1f) {
                    out[i] = p[i] | 0xFF000000;
                } else {
                    out[i] = 0xFF000000
                            | (mix(o[i], p[i], 16, a) << 16)
                            | (mix(o[i], p[i], 8, a) << 8)
                            | mix(o[i], p[i], 0, a);
                }
            }
        });
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static int mix(int a, int b, int shift, float t) {
        int ca = (a >> shift) & 0xFF;
        int cb = (b >> shift) & 0xFF;
        return Math.round(ca + (cb - ca) * t);
    }

    public String normalizeFormat(String format) {
        String f = format == null || format.isBlank() ? "png" : format.toLowerCase();
        if (f.equals("jpg")) {
            f = "jpeg";
        }
        if (!OUTPUT_FORMATS.contains(f)) {
            throw new InvalidImageException("Unsupported output format '" + format + "'. Use png or jpeg.");
        }
        return f;
    }

    public String contentType(String normalizedFormat) {
        return "jpeg".equals(normalizedFormat) ? "image/jpeg" : "image/png";
    }

    public byte[] encode(BufferedImage image, String normalizedFormat) {
        return ImageUtils.encode(image, normalizedFormat);
    }
}
