package com.example.objecteraser.service;

import com.example.objecteraser.dto.EraseOptions;
import com.example.objecteraser.dto.EraseResult;
import com.example.objecteraser.dto.Stage;
import com.example.objecteraser.exception.InpaintingException;
import com.example.objecteraser.service.provider.InpaintingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * Orchestrates the erase pipeline:
 * validate -> dilate mask -> inpaint -> composite/encode, reporting each stage to a listener.
 */
@Service
public class ObjectEraserService {

    private static final Logger log = LoggerFactory.getLogger(ObjectEraserService.class);

    /** Receives a callback when each pipeline stage starts. */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (stage, message) -> {
        };

        void onStage(Stage stage, String message);
    }

    private final ImageProcessingService imageService;
    private final InpaintingProvider provider;

    public ObjectEraserService(ImageProcessingService imageService, InpaintingProvider provider) {
        this.imageService = imageService;
        this.provider = provider;
    }

    public String providerName() {
        return provider.name();
    }

    public EraseResult erase(byte[] imageBytes, byte[] maskBytes, EraseOptions options, ProgressListener listener) {
        long started = System.nanoTime();
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        EraseOptions opts = options == null ? EraseOptions.defaults() : options;

        progress.onStage(Stage.VALIDATING, "Checking the photo and mask");
        String format = imageService.normalizeFormat(opts.format());
        int radius = imageService.resolveGrowMask(opts.growMask());
        BufferedImage image = imageService.readImage(imageBytes);
        BufferedImage mask = imageService.readMask(maskBytes, image.getWidth(), image.getHeight());

        progress.onStage(Stage.DILATING, "Growing the mask by " + radius + " px to cover shadows and halos");
        BufferedImage grown = imageService.dilate(mask, radius);

        progress.onStage(Stage.INPAINTING, "Rebuilding the background with the " + provider.name() + " engine");
        BufferedImage inpainted;
        try {
            inpainted = provider.inpaint(image, grown);
        } catch (InpaintingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InpaintingException("The " + provider.name() + " engine failed: " + e.getMessage(), e);
        }

        progress.onStage(Stage.FINALIZING, "Blending the edges and encoding the result");
        BufferedImage finalImage = imageService.composite(image, inpainted, grown);
        byte[] bytes = imageService.encode(finalImage, format);

        long ms = (System.nanoTime() - started) / 1_000_000;
        log.info("Erased {}x{} image with provider={} grow={}px in {} ms", image.getWidth(), image.getHeight(),
                provider.name(), radius, ms);
        return new EraseResult(bytes, imageService.contentType(format), format,
                finalImage.getWidth(), finalImage.getHeight(), provider.name(), ms);
    }
}
