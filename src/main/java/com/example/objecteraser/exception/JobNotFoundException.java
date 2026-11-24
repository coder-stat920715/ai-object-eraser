package com.example.objecteraser.exception;

/** Unknown or expired job id (HTTP 404). */
public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) {
        super("No job with id '" + jobId + "' (it may have expired).");
    }
}
