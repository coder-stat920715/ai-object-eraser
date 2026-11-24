package com.example.objecteraser.util;

import java.util.Random;

/**
 * Pure-Java, dependency-free inpainting used by the demo provider.
 *
 * <ol>
 *   <li><b>Inward onion-peel fill</b>: the hole is filled layer by layer from its border; each pixel
 *       is the inverse-distance-weighted mean of the already known pixels in a 7x7 window.</li>
 *   <li><b>Diffusion</b>: Gauss-Seidel smoothing passes (forward / backward alternating) relax the fill
 *       towards the harmonic interpolant of the surrounding pixels, removing layering artifacts.</li>
 *   <li><b>Grain matching</b>: the high-frequency noise level of the pixels surrounding the hole is
 *       measured and re-injected so the patch does not look like a flat smudge.</li>
 * </ol>
 * It is excellent on plain/gradient backgrounds (sky, walls, water, sand) and intentionally simple on
 * strong textures - use the {@code onnx} or {@code cloud} provider for those.
 */
public final class DiffusionInpainter {

    private static final int WINDOW = 3;       // 7x7 window for the initial fill
    private static final int SAMPLE_RADIUS = 4;

    private DiffusionInpainter() {
    }

    /**
     * @param rgb    packed RGB pixels (row major)
     * @param mask   {@code != 0} marks pixels to erase
     * @param passes number of Gauss-Seidel smoothing passes
     * @param grain  amount of re-injected noise (0 = none, 1 = same as surroundings)
     * @return new packed pixel array; pixels outside the mask are unchanged
     */
    public static int[] inpaint(int[] rgb, byte[] mask, int w, int h, int passes, double grain, long seed) {
        final int n = w * h;
        final boolean[] hole = new boolean[n];
        int holeCount = 0;
        for (int i = 0; i < n; i++) {
            if (mask[i] != 0) {
                hole[i] = true;
                holeCount++;
            }
        }
        int[] out = rgb.clone();
        if (holeCount == 0) {
            return out;
        }
        if (holeCount == n) {                       // nothing to learn from: neutral grey
            java.util.Arrays.fill(out, 0xFF808080);
            return out;
        }

        final float[] r = new float[n];
        final float[] g = new float[n];
        final float[] b = new float[n];
        final boolean[] known = new boolean[n];
        for (int i = 0; i < n; i++) {
            int p = rgb[i];
            r[i] = (p >> 16) & 0xFF;
            g[i] = (p >> 8) & 0xFF;
            b[i] = p & 0xFF;
            known[i] = !hole[i];
        }

        // ---- 1. onion-peel fill ------------------------------------------------------------
        int[] cur = new int[holeCount];
        int[] next = new int[holeCount];
        int[] order = new int[holeCount];
        float[] tr = new float[holeCount];
        float[] tg = new float[holeCount];
        float[] tb = new float[holeCount];
        boolean[] queued = new boolean[n];
        int curLen = 0;
        int orderLen = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (hole[i] && touchesKnown(known, x, y, w, h)) {
                    cur[curLen++] = i;
                    queued[i] = true;
                }
            }
        }
        final int[] firstLayer = java.util.Arrays.copyOf(cur, curLen);

        while (curLen > 0) {
            for (int k = 0; k < curLen; k++) {
                int i = cur[k];
                int x = i % w;
                int y = i / w;
                double sr = 0, sg = 0, sb = 0, sw = 0;
                for (int dy = -WINDOW; dy <= WINDOW; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) {
                            continue;
                        }
                        int j = yy * w + xx;
                        if (!known[j]) {
                            continue;
                        }
                        double wt = 1.0 / (dx * dx + dy * dy);
                        sr += r[j] * wt;
                        sg += g[j] * wt;
                        sb += b[j] * wt;
                        sw += wt;
                    }
                }
                tr[k] = (float) (sr / sw);
                tg[k] = (float) (sg / sw);
                tb[k] = (float) (sb / sw);
            }
            int nextLen = 0;
            for (int k = 0; k < curLen; k++) {
                int i = cur[k];
                r[i] = tr[k];
                g[i] = tg[k];
                b[i] = tb[k];
                known[i] = true;
                order[orderLen++] = i;
            }
            for (int k = 0; k < curLen; k++) {
                int i = cur[k];
                int x = i % w;
                int y = i / w;
                if (x > 0 && hole[i - 1] && !queued[i - 1]) {
                    queued[i - 1] = true;
                    next[nextLen++] = i - 1;
                }
                if (x < w - 1 && hole[i + 1] && !queued[i + 1]) {
                    queued[i + 1] = true;
                    next[nextLen++] = i + 1;
                }
                if (y > 0 && hole[i - w] && !queued[i - w]) {
                    queued[i - w] = true;
                    next[nextLen++] = i - w;
                }
                if (y < h - 1 && hole[i + w] && !queued[i + w]) {
                    queued[i + w] = true;
                    next[nextLen++] = i + w;
                }
            }
            int[] swap = cur;
            cur = next;
            next = swap;
            curLen = nextLen;
        }

        // ---- 2. diffusion passes -----------------------------------------------------------
        for (int pass = 0; pass < passes; pass++) {
            boolean forward = (pass & 1) == 0;
            for (int k = 0; k < orderLen; k++) {
                int i = forward ? order[k] : order[orderLen - 1 - k];
                int x = i % w;
                int y = i / w;
                float sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                if (x > 0) {
                    sr += r[i - 1];
                    sg += g[i - 1];
                    sb += b[i - 1];
                    cnt++;
                }
                if (x < w - 1) {
                    sr += r[i + 1];
                    sg += g[i + 1];
                    sb += b[i + 1];
                    cnt++;
                }
                if (y > 0) {
                    sr += r[i - w];
                    sg += g[i - w];
                    sb += b[i - w];
                    cnt++;
                }
                if (y < h - 1) {
                    sr += r[i + w];
                    sg += g[i + w];
                    sb += b[i + w];
                    cnt++;
                }
                r[i] = sr / cnt;
                g[i] = sg / cnt;
                b[i] = sb / cnt;
            }
        }

        // ---- 3. grain matching ---------------------------------------------------------------
        double[] sigma = grain > 0 ? measureGrain(rgb, hole, firstLayer, w, h) : new double[]{0, 0, 0};
        Random rng = new Random(seed);
        for (int k = 0; k < orderLen; k++) {
            int i = order[k];
            double noise = rng.nextGaussian() * grain;
            r[i] += (float) (noise * sigma[0]);
            g[i] += (float) (noise * sigma[1]);
            b[i] += (float) (noise * sigma[2]);
            out[i] = 0xFF000000 | (clamp8(r[i]) << 16) | (clamp8(g[i]) << 8) | clamp8(b[i]);
        }
        return out;
    }

    private static boolean touchesKnown(boolean[] known, int x, int y, int w, int h) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if ((dx != 0 || dy != 0) && xx >= 0 && xx < w && yy >= 0 && yy < h && known[yy * w + xx]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** RMS of (pixel - 3x3 local mean) for known pixels near the hole border, per channel. */
    private static double[] measureGrain(int[] rgb, boolean[] hole, int[] borderPixels, int w, int h) {
        double[] sumSq = new double[3];
        long count = 0;
        int step = Math.max(1, borderPixels.length / 4000);
        for (int k = 0; k < borderPixels.length; k += step) {
            int bx = borderPixels[k] % w;
            int by = borderPixels[k] / w;
            for (int dy = -SAMPLE_RADIUS; dy <= SAMPLE_RADIUS; dy += 2) {
                for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx += 2) {
                    int x = bx + dx;
                    int y = by + dy;
                    if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1 || hole[y * w + x]) {
                        continue;
                    }
                    double[] mean = new double[3];
                    int m = 0;
                    boolean clean = true;
                    for (int oy = -1; oy <= 1 && clean; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            int j = (y + oy) * w + (x + ox);
                            if (hole[j]) {
                                clean = false;
                                break;
                            }
                            mean[0] += (rgb[j] >> 16) & 0xFF;
                            mean[1] += (rgb[j] >> 8) & 0xFF;
                            mean[2] += rgb[j] & 0xFF;
                            m++;
                        }
                    }
                    if (!clean) {
                        continue;
                    }
                    int p = rgb[y * w + x];
                    double d0 = ((p >> 16) & 0xFF) - mean[0] / m;
                    double d1 = ((p >> 8) & 0xFF) - mean[1] / m;
                    double d2 = (p & 0xFF) - mean[2] / m;
                    sumSq[0] += d0 * d0;
                    sumSq[1] += d1 * d1;
                    sumSq[2] += d2 * d2;
                    count++;
                }
            }
        }
        if (count == 0) {
            return new double[]{0, 0, 0};
        }
        return new double[]{Math.sqrt(sumSq[0] / count), Math.sqrt(sumSq[1] / count), Math.sqrt(sumSq[2] / count)};
    }

    private static int clamp8(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
