package com.viralguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

/**
 * @Async 비동기 실행을 위한 스레드 풀 설정.
 *
 * ScaleOutExecutor.execute()가 이 풀의 스레드에서 실행됨.
 * Scale-out 작업은 동시에 1개만 실행되면 충분하지만,
 * 안전하게 corePoolSize=2로 설정.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "scaleOutTaskExecutor")
    public Executor scaleOutTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("scale-out-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
