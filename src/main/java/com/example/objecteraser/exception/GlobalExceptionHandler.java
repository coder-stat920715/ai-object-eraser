package com.example.objecteraser.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * Maps every failure to an RFC 7807 {@link ProblemDetail} ({@code application/problem+json}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<ProblemDetail> invalid(InvalidImageException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid image or mask", ex.getMessage(), "invalid-image");
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(JobNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Job not found", ex.getMessage(), "job-not-found");
    }

    @ExceptionHandler(JobNotReadyException.class)
    public ResponseEntity<ProblemDetail> notReady(JobNotReadyException ex) {
        return problem(HttpStatus.CONFLICT, "Result not available", ex.getMessage(), "job-not-ready");
    }

    @ExceptionHandler(InpaintingException.class)
    public ResponseEntity<ProblemDetail> inpainting(InpaintingException ex) {
        log.warn("Inpainting failed: {}", ex.getMessage(), ex);
        return problem(HttpStatus.BAD_GATEWAY, "Inpainting failed", ex.getMessage(), "inpainting-failed");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Upload too large",
                "The uploaded file exceeds the maximum allowed size.", "upload-too-large");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> multipart(MultipartException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid multipart request",
                "Send a multipart/form-data request with 'image' and 'mask' parts.", "invalid-multipart");
    }

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<ProblemDetail> busy(TaskRejectedException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Server is busy",
                "Too many erase jobs are queued. Try again in a few seconds.", "busy");
    }

    /** Spring MVC's own exceptions (missing part, 404, 405, 415 ...) already carry a ProblemDetail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> fallback(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            ProblemDetail body = er.updateAndGetBody(null, Locale.ENGLISH);
            body.setProperty("timestamp", Instant.now().toString());
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
        }
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "Something went wrong while processing the request.", "internal-error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String title, String detail, String type) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("urn:problem:object-eraser:" + type));
        pd.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
