package com.example.objecteraser.dto;

/** Payload of the SSE {@code progress} event. */
public record ProgressEvent(String jobId, String stage, String label, int index, int total, int percent, String message) {

    public static ProgressEvent of(String jobId, Stage stage, String message) {
        return new ProgressEvent(jobId, stage.name(), stage.label(), stage.ordinal(), 4, stage.percent(), message);
    }
}
