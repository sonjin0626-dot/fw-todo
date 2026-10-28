package com.example.todoapp.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.todoapp.exception.BusinessException;
import com.example.todoapp.exception.TodoNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.example.todoapp.api")
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TodoNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(TodoNotFoundException exception, HttpServletRequest request) {
        log.warn("ToDoが見つかりません: path={}, todoId={}", request.getRequestURI(), exception.getTodoId());
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException exception, HttpServletRequest request) {
        log.warn("API業務エラー: path={}, code={}", request.getRequestURI(), exception.getErrorCode());
        return response(HttpStatus.BAD_REQUEST, exception.getErrorCode(), exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        log.warn("APIアクセス拒否: path={}", request.getRequestURI());
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "この操作を行う権限がありません。", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "VALIDATION_ERROR", "入力に誤りがあります", path(request));
        Map<String, String> fieldMessages = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldMessages.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", fieldMessages.entrySet().stream()
                .map(error -> Map.of("field", error.getKey(), "message", error.getValue())).toList());
        log.warn("API入力エラー: path={}", path(request));
        return ResponseEntity.status(status).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("APIシステムエラー: path={}", path(request), exception);
        } else {
            log.warn("APIリクエストエラー: path={}, status={}, type={}",
                    path(request), status.value(), exception.getClass().getSimpleName());
        }
        String code = status.is5xxServerError() ? "INTERNAL_SERVER_ERROR"
                : status.value() == 404 ? "NOT_FOUND" : "HTTP_" + status.value();
        String message = status.is5xxServerError() ? "システムエラーが発生しました。時間をおいて再度お試しください。"
                : "リクエストを処理できませんでした。入力内容やリクエスト形式を確認してください。";
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, code, message, path(request)));
    }

    public ResponseEntity<Object> handleHttpError(Exception exception, ErrorResponse error, WebRequest request) {
        return handleExceptionInternal(exception, null, error.getHeaders(), error.getStatusCode(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("APIシステムエラー: path={}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "システムエラーが発生しました。時間をおいて再度お試しください。", request.getRequestURI());
    }

    private ResponseEntity<Object> response(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, code, message, path));
    }

    private ProblemDetail problem(HttpStatusCode status, String code, String message, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }

    private String path(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
