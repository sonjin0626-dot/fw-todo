package com.example.todoapp.aspect;

import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 正常終了・例外発生のどちらでもServiceの実行時間を記録する。 */
@Aspect
@Component
@Order(1)
public class PerformanceAspect {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceAspect.class);

    @Around("com.example.todoapp.aspect.LoggingAspect.serviceLayer()")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            logger.info("{} 実行時間: {}ms", joinPoint.getSignature().toShortString(), elapsedMillis(startTime));
            return result;
        } catch (Throwable ex) {
            logger.error("{} 実行時間: {}ms (例外発生)",
                    joinPoint.getSignature().toShortString(), elapsedMillis(startTime));
            throw ex;
        }
    }

    private long elapsedMillis(long startTime) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    }
}
