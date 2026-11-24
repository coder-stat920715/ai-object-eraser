package com.example.objecteraser.service;

import com.example.objecteraser.config.EraserProperties;
import com.example.objecteraser.dto.EraseOptions;
import com.example.objecteraser.dto.EraseResult;
import com.example.objecteraser.dto.ProgressEvent;
import com.example.objecteraser.dto.Stage;
import com.example.objecteraser.exception.JobNotFoundException;
import com.example.objecteraser.exception.JobNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs erase jobs on a bounded executor and exposes their progress as Server-Sent Events.
 * Finished jobs live in memory for {@code ai.eraser.jobs.ttl} and are then purged.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ObjectEraserService eraser;
    private final ThreadPoolTaskExecutor executor;
    private final EraserProperties props;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public JobService(ObjectEraserService eraser,
                      @Qualifier("eraserExecutor") ThreadPoolTaskExecutor executor,
                      EraserProperties props) {
        this.eraser = eraser;
        this.executor = executor;
        this.props = props;
    }

    /**
     * Queues a job. Throws {@link org.springframework.core.task.TaskRejectedException} (mapped to HTTP 429)
     * when the queue is full.
     */
    public String submit(byte[] image, byte[] mask, EraseOptions options) {
        String id = UUID.randomUUID().toString();
        Job job = new Job(id);
        jobs.put(id, job);
        job.progress(new ProgressEvent(id, "QUEUED", "Queued", -1, 4, 0, "Waiting for a free worker"));
        try {
            executor.execute(() -> run(job, image, mask, options));
        } catch (RuntimeException e) {
            jobs.remove(id);
            throw e;
        }
        return id;
    }

    private void run(Job job, byte[] image, byte[] mask, EraseOptions options) {
        try {
            EraseResult result = eraser.erase(image, mask, options,
                    (stage, message) -> job.progress(ProgressEvent.of(job.id(), stage, message)));
            job.complete(result, Map.of(
                    "jobId", job.id(),
                    "stage", Stage.DONE.name(),
                    "percent", Stage.DONE.percent(),
                    "resultUrl", "/api/v1/erase/result/" + job.id(),
                    "contentType", result.contentType(),
                    "width", result.width(),
                    "height", result.height(),
                    "provider", result.provider(),
                    "durationMs", result.durationMs()));
        } catch (Exception e) {
            log.warn("Job {} failed: {}", job.id(), e.getMessage());
            HttpStatus status = e instanceof com.example.objecteraser.exception.InvalidImageException
                    ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
            pd.setTitle(status == HttpStatus.BAD_REQUEST ? "Invalid image or mask" : "Inpainting failed");
            pd.setType(URI.create("urn:problem:object-eraser:job-failed"));
            job.fail(pd);
        }
    }

    public SseEmitter subscribe(String jobId) {
        Job job = find(jobId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        job.subscribe(emitter);
        return emitter;
    }

    public EraseResult result(String jobId) {
        Job job = find(jobId);
        return switch (job.status()) {
            case DONE -> job.result();
            case RUNNING -> throw new JobNotReadyException("The job is still running.");
            case FAILED -> throw new JobNotReadyException("The job failed; there is no result.");
        };
    }

    private Job find(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void purgeExpired() {
        Instant cutoff = Instant.now().minus(props.jobs().ttl());
        jobs.values().removeIf(j -> j.finishedAt() != null && j.finishedAt().isBefore(cutoff));
    }
}
