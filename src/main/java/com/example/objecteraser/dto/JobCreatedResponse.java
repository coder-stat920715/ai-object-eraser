package com.example.objecteraser.dto;

/** Response of {@code POST /api/v1/erase/async}. */
public record JobCreatedResponse(String jobId, String streamUrl, String resultUrl) {
}
