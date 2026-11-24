package com.example.objecteraser.exception;

/** The uploaded image or mask is missing, malformed, too large or otherwise unusable (HTTP 400). */
public class InvalidImageException extends RuntimeException {
    public InvalidImageException(String message) {
        super(message);
    }

    public InvalidImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
