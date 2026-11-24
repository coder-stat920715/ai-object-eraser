package com.example.objecteraser.service;

import com.example.objecteraser.dto.EraseResult;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * State of one asynchronous erase job. Events are kept so a client that connects to the SSE stream
 * after the job has started (or finished) still receives the full history.
 */
final class Job {

    enum Status { RUNNING, DONE, FAILED }

    private record Event(String name, Object data) {
    }

    private final String id;
    private final Instant createdAt = Instant.now();
    private final List<Event> history = new ArrayList<>();
    private final List<SseEmitter> emitters = new ArrayList<>();
    private Status status = Status.RUNNING;
    private EraseResult result;
    private Instant finishedAt;

    Job(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    Instant createdAt() {
        return createdAt;
    }

    synchronized Status status() {
        return status;
    }

    synchronized EraseResult result() {
        return result;
    }

    synchronized Instant finishedAt() {
        return finishedAt;
    }

    synchronized void progress(Object data) {
        record("progress", data);
    }

    synchronized void complete(EraseResult erased, Object completeEvent) {
        this.result = erased;
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
        record("complete", completeEvent);
        closeEmitters();
    }

    synchronized void fail(Object errorEvent) {
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
        record("failure", errorEvent);
        closeEmitters();
    }

    /** Replays the history to a new subscriber and keeps it for live events while the job runs. */
    synchronized void subscribe(SseEmitter emitter) {
        for (Event e : history) {
            if (!send(emitter, e)) {
                return;
            }
        }
        if (status == Status.RUNNING) {
            emitters.add(emitter);
            Runnable remove = () -> {
                synchronized (Job.this) {
                    emitters.remove(emitter);
                }
            };
            emitter.onCompletion(remove);
            emitter.onTimeout(remove);
            emitter.onError(t -> remove.run());
        } else {
            emitter.complete();
        }
    }

    private void record(String name, Object data) {
        Event event = new Event(name, data);
        history.add(event);
        emitters.removeIf(emitter -> !send(emitter, event));
    }

    private void closeEmitters() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    private static boolean send(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;   // client went away
        }
    }
}
