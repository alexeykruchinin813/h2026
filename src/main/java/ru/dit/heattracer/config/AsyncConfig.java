package ru.dit.heattracer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "calcExecutor")
    public Executor calcExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);        // 2 задачи параллельно
        executor.setMaxPoolSize(2);         // не больше 2 одновременно
        executor.setQueueCapacity(50);      // ещё 50 в очереди
        executor.setThreadNamePrefix("calc-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}