package com.example.objecteraser.dto;

/** Response of {@code GET /api/v1/info}; lets the UI show the active engine and limits. */
public record ServiceInfo(String provider, int defaultGrowMask, long maxFileBytes, long maxPixels) {
}
