package com.example.objecteraser.dto;

/**
 * Per-request options.
 *
 * @param growMask number of pixels the mask is dilated by; {@code null} uses the configured default
 * @param format   output encoding, {@code png} (default) or {@code jpeg}
 */
public record EraseOptions(Integer growMask, String format) {

    public static EraseOptions defaults() {
        return new EraseOptions(null, "png");
    }

    public String formatOrDefault() {
        return format == null || format.isBlank() ? "png" : format.toLowerCase();
    }
}
