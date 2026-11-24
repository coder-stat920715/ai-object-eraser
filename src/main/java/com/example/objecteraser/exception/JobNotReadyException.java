package com.example.objecteraser.exception;

/** The job exists but has no result yet, or it failed (HTTP 409). */
public class JobNotReadyException extends RuntimeException {
    public JobNotReadyException(String message) {
        super(message);
    }
}
