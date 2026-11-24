package com.example.objecteraser.exception;

/** The inpainting engine failed (HTTP 502). */
public class InpaintingException extends RuntimeException {
    public InpaintingException(String message) {
        super(message);
    }

    public InpaintingException(String message, Throwable cause) {
        super(message, cause);
    }
}
