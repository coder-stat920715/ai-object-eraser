package com.example.objecteraser.controller;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.dto.EraseOptions;
import com.example.objecteraser.dto.EraseResult;
import com.example.objecteraser.dto.JobCreatedResponse;
import com.example.objecteraser.dto.ServiceInfo;
import com.example.objecteraser.exception.InvalidImageException;
import com.example.objecteraser.service.JobService;
import com.example.objecteraser.service.ObjectEraserService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class ObjectEraserController {

    private final ObjectEraserService eraser;
    private final JobService jobs;
    private final EraserProperties props;

    public ObjectEraserController(ObjectEraserService eraser, JobService jobs, EraserProperties props) {
        this.eraser = eraser;
        this.jobs = jobs;
        this.props = props;
    }

    /** Active engine and limits, used by the UI. */
    @GetMapping("/info")
    public ServiceInfo info() {
        return new ServiceInfo(eraser.providerName(), props.dilationRadius(),
                props.limits().maxFileSize().toBytes(), props.limits().maxPixels());
    }

    /** Synchronous erase: returns the processed image bytes. */
    @PostMapping(value = "/erase", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> erase(@RequestParam("image") MultipartFile image,
                                        @RequestParam("mask") MultipartFile mask,
                                        @RequestParam(value = "growMask", required = false) Integer growMask,
                                        @RequestParam(value = "format", required = false) String format) {
        EraseResult result = eraser.erase(read(image, "image"), read(mask, "mask"),
                new EraseOptions(growMask, format), null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"erased." + ("jpeg".equals(result.format()) ? "jpg" : "png") + "\"")
                .header("X-Eraser-Provider", result.provider())
                .header("X-Eraser-Duration-Ms", String.valueOf(result.durationMs()))
                .body(result.bytes());
    }

    /** Starts an asynchronous job; follow it on {@code streamUrl} and fetch the image from {@code resultUrl}. */
    @PostMapping(value = "/erase/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobCreatedResponse> eraseAsync(@RequestParam("image") MultipartFile image,
                                                         @RequestParam("mask") MultipartFile mask,
                                                         @RequestParam(value = "growMask", required = false) Integer growMask,
                                                         @RequestParam(value = "format", required = false) String format) {
        String jobId = jobs.submit(read(image, "image"), read(mask, "mask"), new EraseOptions(growMask, format));
        JobCreatedResponse body = new JobCreatedResponse(jobId,
                "/api/v1/erase/stream/" + jobId, "/api/v1/erase/result/" + jobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** Server-Sent Events: {@code progress}* then {@code complete} or {@code failure}. */
    @GetMapping(value = "/erase/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        return jobs.subscribe(jobId);
    }

    /** The finished image of an async job. */
    @GetMapping("/erase/result/{jobId}")
    public ResponseEntity<byte[]> result(@PathVariable String jobId) {
        EraseResult result = jobs.result(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Eraser-Provider", result.provider())
                .body(result.bytes());
    }

    private static byte[] read(MultipartFile file, String label) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidImageException("The " + label + " upload could not be read.", e);
        }
    }
}
