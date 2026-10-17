package com.lue.erp.images;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ImageArchiveProperties.class)
public class ImageArchiveConfiguration {
    @Bean("imageArchiveExecutor")
    Executor imageArchiveExecutor(ImageArchiveProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("image-archive-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
