package com.example.todoapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
public class AsyncConfig implements AsyncConfigurer {
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return executor(5, 10, 25, "Async-");
    }

    // 登録確定後のメール通知用プール。
    @Bean(name = "emailExecutor")
    public ThreadPoolTaskExecutor emailExecutor() {
        return executor(2, 5, 100, "Email-");
    }

    private ThreadPoolTaskExecutor executor(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(task -> {
            // 呼び出し時の認証を別Contextに保存し、処理後にはワーカースレッドから除去する。
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
            return new DelegatingSecurityContextRunnable(task, context);
        });
        // 初期化・停止はSpring Beanのライフサイクルに任せる。
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // 引数には個人情報が含まれるため記録しない。
        return (exception, method, params) -> log.error("非同期処理に失敗: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName(), exception);
    }
}
