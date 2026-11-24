package com.example.objecteraser.dto;

/** The ordered processing stages that are streamed to the browser. */
public enum Stage {
    VALIDATING("Uploading & Validating", 10),
    DILATING("Dilating Mask Edges", 35),
    INPAINTING("AI Inpainting", 60),
    FINALIZING("Harmonizing & Finalizing", 90),
    DONE("Done", 100);

    private final String label;
    private final int percent;

    Stage(String label, int percent) {
        this.label = label;
        this.percent = percent;
    }

    public String label() {
        return label;
    }

    public int percent() {
        return percent;
    }
}
