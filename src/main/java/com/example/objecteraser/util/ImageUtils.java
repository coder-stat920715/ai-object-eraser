package com.example.objecteraser.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Low-level, framework-free image helpers. Masks are represented as
 * {@link BufferedImage#TYPE_BYTE_GRAY} images (or raw {@code byte[]}) where
 * {@code 0} = keep and {@code 255} = erase.
 */
public final class ImageUtils {

    private ImageUtils() {
    }

    /** Returns an opaque {@link BufferedImage#TYPE_INT_RGB} copy; transparent areas are flattened onto white. */
    public static BufferedImage toRgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Converts any decoded image into a strictly binary (0/255) single-channel mask. */
    public static BufferedImage toBinaryMask(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        byte[] data = new byte[w * h];
        for (int i = 0; i < data.length; i++) {
            int p = argb[i];
            int alpha = p >>> 24;
            int lum = Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF));
            data[i] = (alpha >= 128 && lum > 127) ? (byte) 255 : 0;
        }
        return maskFromBytes(data, w, h);
    }

    public static byte[] maskBytes(BufferedImage mask) {
        return (byte[]) mask.getRaster().getDataElements(0, 0, mask.getWidth(), mask.getHeight(), null);
    }

    public static BufferedImage maskFromBytes(byte[] data, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        img.getRaster().setDataElements(0, 0, w, h, data);
        return img;
    }

    public static boolean hasMarkedPixels(byte[] mask) {
        for (byte b : mask) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    /** High quality bilinear/bicubic resize into an RGB image. */
    public static BufferedImage resizeSmooth(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Nearest-neighbour resize that keeps the mask strictly binary. */
    public static byte[] resizeMaskNearest(byte[] src, int sw, int sh, int dw, int dh) {
        byte[] out = new byte[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) ((y + 0.5) * sh / dh));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) ((x + 0.5) * sw / dw));
                out[y * dw + x] = src[sy * sw + sx];
            }
        }
        return out;
    }

    /**
     * Morphological dilation with a disc-shaped structuring element of the given radius.
     * Uses per-row prefix sums, so the cost is O(radius * pixels) regardless of mask size.
     */
    public static byte[] dilate(byte[] mask, int w, int h, int radius) {
        if (radius <= 0) {
            return mask.clone();
        }
        final int stride = w + 1;
        final int[] prefix = new int[stride * h];
        for (int y = 0; y < h; y++) {
            int base = y * stride;
            for (int x = 0; x < w; x++) {
                prefix[base + x + 1] = prefix[base + x] + (mask[y * w + x] != 0 ? 1 : 0);
            }
        }
        final int[] span = new int[2 * radius + 1];
        for (int dy = -radius; dy <= radius; dy++) {
            span[dy + radius] = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dy * dy) + 0.5);
        }
        final byte[] out = new byte[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                if (mask[y * w + x] != 0) {
                    out[y * w + x] = (byte) 255;
                    continue;
                }
                boolean hit = false;
                for (int k = 1; k <= radius && !hit; k++) {          // walk outwards from the centre row
                    for (int dy = -k; dy <= k && !hit; dy += 2 * k) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) {
                            continue;
                        }
                        int sp = span[dy + radius];
                        int x0 = Math.max(0, x - sp);
                        int x1 = Math.min(w - 1, x + sp);
                        hit = prefix[yy * stride + x1 + 1] - prefix[yy * stride + x0] > 0;
                    }
                }
                if (!hit) {                                           // centre row (dy = 0)
                    int sp = span[radius];
                    int x0 = Math.max(0, x - sp);
                    int x1 = Math.min(w - 1, x + sp);
                    hit = prefix[y * stride + x1 + 1] - prefix[y * stride + x0] > 0;
                }
                out[y * w + x] = hit ? (byte) 255 : 0;
            }
        });
        return out;
    }

    /** Separable box blur with clamped edges. */
    public static float[] boxBlur(float[] src, int w, int h, int r) {
        if (r <= 0) {
            return src.clone();
        }
        float[] tmp = new float[w * h];
        float[] dst = new float[w * h];
        float norm = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float sum = 0;
            for (int k = -r; k <= r; k++) {
                sum += src[row + clamp(k, 0, w - 1)];
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = sum * norm;
                sum += src[row + clamp(x + r + 1, 0, w - 1)] - src[row + clamp(x - r, 0, w - 1)];
            }
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int k = -r; k <= r; k++) {
                sum += tmp[clamp(k, 0, h - 1) * w + x];
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = sum * norm;
                sum += tmp[clamp(y + r + 1, 0, h - 1) * w + x] - tmp[clamp(y - r, 0, h - 1) * w + x];
            }
        }
        return dst;
    }

    public static byte[] encode(BufferedImage image, String format) {
        String fmt = "jpeg".equals(format) ? "jpg" : format;
        BufferedImage toWrite = "jpg".equals(fmt) && image.getType() != BufferedImage.TYPE_INT_RGB ? toRgb(image) : image;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16)) {
            if (!ImageIO.write(toWrite, fmt, bos)) {
                throw new IllegalStateException("No ImageIO writer for format " + format);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
