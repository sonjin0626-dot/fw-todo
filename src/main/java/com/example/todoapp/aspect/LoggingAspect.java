package com.example.todoapp.aspect;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import com.example.todoapp.User;
import com.example.todoapp.UserEditForm;

/** Serviceの開始・正常終了・例外を共通で記録する。 */
@Aspect
@Component
@Order(0)
public class LoggingAspect {
    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);

    // serviceパッケージではなく、既存の@Serviceクラスを対象にする。
    @Pointcut("@within(org.springframework.stereotype.Service) && execution(public * com.example.todoapp..*(..))"
            + " && !within(com.example.todoapp.MailService)"
            + " && !within(com.example.todoapp.AuditService)"
            + " && !within(com.example.todoapp.AuditLogService)")
    public void serviceLayer() {
    }

    @Before("serviceLayer()")
    public void logBefore(JoinPoint joinPoint) {
        if (logger.isInfoEnabled()) {
            logger.info("【開始】{} 引数: {}", joinPoint.getSignature().toShortString(),
                    Arrays.stream(joinPoint.getArgs()).map(this::formatValue).toList());
        }
    }

    @AfterReturning(pointcut = "serviceLayer()", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result) {
        if (logger.isInfoEnabled()) {
            logger.info("【終了】{} 戻り値: {}", joinPoint.getSignature().toShortString(), formatValue(result));
        }
    }

    @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable ex) {
        logger.error("【例外】{} エラー: {}", joinPoint.getSignature().toShortString(), ex.getMessage());
    }

    private String formatValue(Object value) {
        // 認証・ユーザー管理Serviceも対象になるため、パスワードを含む型は明示的に整形する。
        if (value instanceof UserEditForm form) {
            return "UserEditForm(username=" + form.getUsername() + ", role=" + form.getRole()
                    + ", enabled=" + form.isEnabled() + ", newPassword=[PROTECTED])";
        }
        if (value instanceof User user) {
            return "User(id=" + user.getId() + ", username=" + user.getUsername() + ", role=" + user.getRole()
                    + ", enabled=" + user.isEnabled() + ", password=[PROTECTED])";
        }
        if (value instanceof UserDetails user) {
            return "UserDetails(username=" + user.getUsername() + ", authorities=" + user.getAuthorities()
                    + ", enabled=" + user.isEnabled() + ", password=[PROTECTED])";
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::formatValue).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(value);
    }
}
