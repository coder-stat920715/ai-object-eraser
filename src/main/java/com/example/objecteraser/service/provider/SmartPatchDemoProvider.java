package com.example.objecteraser.service.provider;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.util.DiffusionInpainter;
import com.example.objecteraser.util.ImageUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * Default provider: a pure-Java inward-diffusion / surrounding-pixel inpainting algorithm. It needs
 * no API key and no model file, so the application visually erases objects immediately after start-up.
 */
@Component
@ConditionalOnProperty(name = "ai.eraser.provider", havingValue = "demo", matchIfMissing = true)
public class SmartPatchDemoProvider implements InpaintingProvider {

    private final EraserProperties.Demo cfg;

    public SmartPatchDemoProvider(EraserProperties props) {
        this.cfg = props.demo();
    }

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public BufferedImage inpaint(BufferedImage image, BufferedImage mask) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        int[] filled = DiffusionInpainter.inpaint(rgb, ImageUtils.maskBytes(mask), w, h,
                cfg.smoothingPasses(), cfg.grain(), cfg.seed());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, filled, 0, w);
        return out;
    }
}
