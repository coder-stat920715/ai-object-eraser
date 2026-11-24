package com.example.objecteraser.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly typed binding for everything under {@code ai.eraser.*}.
 */
@Validated
@ConfigurationProperties(prefix = "ai.eraser")
public record EraserProperties(
        @DefaultValue("demo") @Pattern(regexp = "demo|cloud|onnx", message = "provider must be demo, cloud or onnx") String provider,
        @DefaultValue("6") @Min(0) @Max(64) int dilationRadius,
        @DefaultValue("2") @Min(0) @Max(16) int featherRadius,
        @Valid @DefaultValue Limits limits,
        @Valid @DefaultValue Jobs jobs,
        @Valid @DefaultValue Demo demo,
        @Valid @DefaultValue Cloud cloud,
        @Valid @DefaultValue Onnx onnx) {

    public record Limits(
            @DefaultValue("20MB") DataSize maxFileSize,
            @DefaultValue("16000000") @Positive long maxPixels) {
    }

    public record Jobs(
            @DefaultValue("2") @Min(1) int concurrency,
            @DefaultValue("16") @Min(0) int queueCapacity,
            @DefaultValue("10m") Duration ttl) {
    }

    public record Demo(
            @DefaultValue("48") @Min(0) int smoothingPasses,
            @DefaultValue("0.8") @DecimalMin("0.0") @DecimalMax("2.0") double grain,
            @DefaultValue("42") long seed) {
    }

    public record Cloud(
            @DefaultValue("https://api.stability.ai") @NotBlank String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("Authorization") @NotBlank String apiKeyHeader,
            @DefaultValue("Bearer ") String apiKeyPrefix,
            @DefaultValue("0") @Min(0) @Max(20) int growMask,
            @DefaultValue("png") @Pattern(regexp = "png|jpeg|webp") String outputFormat,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("120s") Duration readTimeout) {
    }

    public record Onnx(
            @DefaultValue("models/lama_fp32.onnx") @NotBlank String modelPath,
            @DefaultValue("512") @Min(64) int inputSize,
            @DefaultValue("0.6") @DecimalMin("0.0") double contextMargin,
            @DefaultValue("image") @NotBlank String imageInputName,
            @DefaultValue("mask") @NotBlank String maskInputName) {
    }
}
