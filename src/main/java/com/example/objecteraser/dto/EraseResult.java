package com.example.objecteraser.dto;

/** Final output of a successful erase operation. */
public record EraseResult(byte[] bytes, String contentType, String format, int width, int height,
                          String provider, long durationMs) {
}
