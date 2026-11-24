package com.example.objecteraser.service.provider;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;
import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.exception.InpaintingException;
import com.example.objecteraser.util.ImageUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a LaMa inpainting model (fixed-size ONNX export, e.g. Carve/LaMa-ONNX) on the JVM using
 * Deep Java Library + ONNX Runtime. Because the model works on {@code input-size x input-size} tiles,
 * the area around the mask is cropped (with context), inpainted, scaled back and pasted in.
 */
@Component
@ConditionalOnProperty(name = "ai.eraser.provider", havingValue = "onnx")
public class OnnxLocalInpaintingProvider implements InpaintingProvider {

    private static final Logger log = LoggerFactory.getLogger(OnnxLocalInpaintingProvider.class);

    private final EraserProperties.Onnx cfg;
    private ZooModel<NDList, NDList> model;

    public OnnxLocalInpaintingProvider(EraserProperties props) {
        this.cfg = props.onnx();
    }

    @PostConstruct
    void load() {
        Path file = Path.of(cfg.modelPath()).toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("ONNX model not found at " + file
                    + ". Download a LaMa .onnx file and set ai.eraser.onnx.model-path (see models/README.md).");
        }
        String fileName = file.getFileName().toString();
        String modelName = fileName.endsWith(".onnx") ? fileName.substring(0, fileName.length() - 5) : fileName;
        try {
            Criteria<NDList, NDList> criteria = Criteria.builder()
                    .setTypes(NDList.class, NDList.class)
                    .optModelPath(file.getParent())
                    .optModelName(modelName)
                    .optEngine("OnnxRuntime")
                    .optTranslator(new NoopTranslator())
                    .build();
            this.model = criteria.loadModel();
            log.info("Loaded ONNX inpainting model {} ({}x{})", file, cfg.inputSize(), cfg.inputSize());
        } catch (IOException | ModelNotFoundException | MalformedModelException e) {
            throw new IllegalStateException("Failed to load ONNX model " + file + ": " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void close() {
        if (model != null) {
            model.close();
        }
    }

    @Override
    public String name() {
        return "onnx";
    }

    @Override
    public BufferedImage inpaint(BufferedImage image, BufferedImage mask) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int s = cfg.inputSize();
        final byte[] m = ImageUtils.maskBytes(mask);

        int[] crop = cropWindow(m, w, h, s);          // x, y, width, height
        int cx = crop[0], cy = crop[1], cw = crop[2], ch = crop[3];

        BufferedImage tile = ImageUtils.resizeSmooth(image.getSubimage(cx, cy, cw, ch), s, s);
        byte[] cropMask = new byte[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(m, (cy + y) * w + cx, cropMask, y * cw, cw);
        }
        byte[] tileMask = ImageUtils.resizeMaskNearest(cropMask, cw, ch, s, s);

        float[] imgData = new float[3 * s * s];
        int[] px = tile.getRGB(0, 0, s, s, null, 0, s);
        int plane = s * s;
        for (int i = 0; i < plane; i++) {
            imgData[i] = ((px[i] >> 16) & 0xFF) / 255f;
            imgData[plane + i] = ((px[i] >> 8) & 0xFF) / 255f;
            imgData[2 * plane + i] = (px[i] & 0xFF) / 255f;
        }
        float[] maskData = new float[plane];
        for (int i = 0; i < plane; i++) {
            maskData[i] = tileMask[i] != 0 ? 1f : 0f;
        }

        float[] out;
        try (NDManager manager = model.getNDManager().newSubManager();
             Predictor<NDList, NDList> predictor = model.newPredictor()) {
            NDArray imageArray = manager.create(imgData, new Shape(1, 3, s, s));
            imageArray.setName(cfg.imageInputName());
            NDArray maskArray = manager.create(maskData, new Shape(1, 1, s, s));
            maskArray.setName(cfg.maskInputName());
            NDList result = predictor.predict(new NDList(imageArray, maskArray));
            out = result.get(0).toFloatArray();
        } catch (TranslateException | RuntimeException e) {
            throw new InpaintingException("ONNX inference failed: " + e.getMessage(), e);
        }
        if (out.length < 3 * plane) {
            throw new InpaintingException("Unexpected ONNX output size " + out.length + " (expected " + 3 * plane
                    + "). Is this a " + s + "x" + s + " LaMa model?");
        }

        // Model output is either 0..255 (Carve/LaMa-ONNX) or 0..1; detect it.
        float max = 0;
        for (int i = 0; i < 3 * plane; i++) {
            max = Math.max(max, out[i]);
        }
        float scale = max <= 1.5f ? 255f : 1f;
        int[] outPx = new int[plane];
        for (int i = 0; i < plane; i++) {
            outPx[i] = 0xFF000000
                    | (to8(out[i] * scale) << 16)
                    | (to8(out[plane + i] * scale) << 8)
                    | to8(out[2 * plane + i] * scale);
        }
        BufferedImage tileOut = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        tileOut.setRGB(0, 0, s, s, outPx, 0, s);
        BufferedImage restored = ImageUtils.resizeSmooth(tileOut, cw, ch);
        int[] restoredPx = restored.getRGB(0, 0, cw, ch, null, 0, cw);

        // Paste only the masked pixels back into a copy of the original.
        int[] result = image.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int idx = (cy + y) * w + (cx + x);
                if (m[idx] != 0) {
                    result[idx] = restoredPx[y * cw + x];
                }
            }
        }
        BufferedImage composed = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        composed.setRGB(0, 0, w, h, result, 0, w);
        return composed;
    }

    /** Square window around the mask bounding box (plus context), or the whole image if the mask is huge. */
    private int[] cropWindow(byte[] m, int w, int h, int s) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (m[y * w + x] != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return new int[]{0, 0, w, h};
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        int minDim = Math.min(w, h);
        double side = Math.max(bw, bh) * (1.0 + 2.0 * cfg.contextMargin());
        side = Math.max(side, Math.min(s, minDim));
        int cs = (int) Math.min(Math.ceil(side), minDim);
        if (Math.max(bw, bh) > cs) {
            return new int[]{0, 0, w, h};
        }
        int cx = ImageUtils.clamp((minX + maxX) / 2 - cs / 2, 0, w - cs);
        int cy = ImageUtils.clamp((minY + maxY) / 2 - cs / 2, 0, h - cs);
        return new int[]{cx, cy, cs, cs};
    }

    private static int to8(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
