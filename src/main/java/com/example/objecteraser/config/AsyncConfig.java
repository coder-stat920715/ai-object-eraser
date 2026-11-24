package com.example.objecteraser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for the asynchronous (SSE) erase pipeline. Inpainting is
 * CPU/GPU heavy, so concurrency is capped; overflow is rejected and mapped to HTTP 429.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "eraserExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor eraserExecutor(EraserProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.jobs().concurrency());
        executor.setMaxPoolSize(props.jobs().concurrency());
        executor.setQueueCapacity(props.jobs().queueCapacity());
        executor.setThreadNamePrefix("eraser-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
