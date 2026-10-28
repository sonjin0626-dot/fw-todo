package com.example.todoapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.context.request.ServletWebRequest;
import com.example.todoapp.api.RestExceptionHandler;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {
    private final RestExceptionHandler restHandler;

    public GlobalExceptionHandler(RestExceptionHandler restHandler) {
        this.restHandler = restHandler;
    }

    @ExceptionHandler(TodoNotFoundException.class)
    public String handleTodoNotFound(TodoNotFoundException exception, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        log.warn("ToDoが見つかりません: path={}, todoId={}", request.getRequestURI(), exception.getTodoId());
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("errorMessage", "指定されたToDoが見つかりません。削除された可能性があります。");
        model.addAttribute("todoId", exception.getTodoId());
        return "error/404";
    }

    @ExceptionHandler(BusinessException.class)
    public String handleBusiness(BusinessException exception, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        log.warn("業務エラー: path={}, code={}", request.getRequestURI(), exception.getErrorCode());
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("errorCode", exception.getErrorCode());
        model.addAttribute("errorMessage", exception.getMessage());
        return "error/business";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException exception,
            HttpServletRequest request, HttpServletResponse response) {
        log.warn("アクセス拒否: path={}", request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return "error/403";
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception exception, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        // URL解決前の405や、存在しない/api/**もJSONで返す。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api") || path.startsWith("/api/")) {
            if (exception instanceof ErrorResponse error) {
                return restHandler.handleHttpError(exception, error, new ServletWebRequest(request));
            }
            return restHandler.handleUnexpected(exception, request);
        }
        // Springが判定した400・404・405等を、500へ変えてしまわない。
        int status = statusFor(exception);
        response.setStatus(status);
        if (exception instanceof ErrorResponse error) {
            error.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        }
        if (status >= 500) {
            log.error("システムエラー: path={}", request.getRequestURI(), exception);
            model.addAttribute("errorMessage", "システムエラーが発生しました。時間をおいて再度お試しください。");
            return new ModelAndView("error/500", model.asMap());
        }
        log.warn("リクエストエラー: path={}, status={}, type={}",
                request.getRequestURI(), status, exception.getClass().getSimpleName());
        if (status == 404) {
            model.addAttribute("errorMessage", "指定されたページまたはデータが見つかりません。");
            return new ModelAndView("error/404", model.asMap());
        }
        if (status == 403) {
            return new ModelAndView("error/403", model.asMap());
        }
        model.addAttribute("errorCode", "HTTP_" + status);
        model.addAttribute("errorMessage", "リクエストを処理できませんでした。入力内容や操作手順を確認してください。");
        return new ModelAndView("error/business", model.asMap());
    }

    private int statusFor(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            return error.getStatusCode().value();
        }
        // 型変換失敗など、一部のSpring MVC入力例外はErrorResponseを実装していない。
        if ((exception instanceof TypeMismatchException && !(exception instanceof ConversionNotSupportedException))
                || exception instanceof HttpMessageNotReadableException || exception instanceof BindException) {
            return HttpStatus.BAD_REQUEST.value();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
