package com.example.objecteraser.service.provider;

import com.example.objecteraser.exception.InpaintingException;

import java.awt.image.BufferedImage;

/**
 * Strategy interface for an inpainting engine. Exactly one implementation is active,
 * selected with {@code ai.eraser.provider} ({@code demo}, {@code cloud} or {@code onnx}).
 */
public interface InpaintingProvider {

    /** Short identifier shown in the UI and in the {@code X-Eraser-Provider} response header. */
    String name();

    /**
     * Removes the masked area from the image.
     *
     * @param image RGB photo
     * @param mask  {@link BufferedImage#TYPE_BYTE_GRAY} mask of identical size; 255 = erase, 0 = keep
     * @return an image with the masked region reconstructed (any size; the caller realigns it)
     * @throws InpaintingException if the engine fails
     */
    BufferedImage inpaint(BufferedImage image, BufferedImage mask) throws InpaintingException;
}
