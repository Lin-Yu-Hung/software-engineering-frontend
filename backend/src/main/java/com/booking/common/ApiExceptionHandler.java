package com.booking.common;

import com.booking.court.CourtNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(CourtNotFoundException.class)
    ResponseEntity<ApiError> notFound(CourtNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "COURT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "參數格式不正確", request);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ValidationErrorResponse> invalidFields(MethodArgumentNotValidException exception,
                                                         HttpServletRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new ValidationErrorResponse.FieldViolation(field.getField(), field.getDefaultMessage()))
                .distinct().sorted(Comparator.comparing(ValidationErrorResponse.FieldViolation::field)
                        .thenComparing(ValidationErrorResponse.FieldViolation::message)).toList();
        String message = fields.stream().map(field -> field.field() + "：" + field.message())
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(new ValidationErrorResponse("VALIDATION_FAILED",
                message.isEmpty() ? "請檢查必填欄位、格式與長度" : message,
                request.getRequestURI(), Instant.now(), fields));
    }

    @ExceptionHandler({HandlerMethodValidationException.class,
            ServletRequestBindingException.class})
    ResponseEntity<ApiError> validation(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "請檢查必填欄位、格式與長度", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidBody(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_BODY", "JSON 格式、欄位或欄位值不正確", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "資料重複或與現有資料衝突", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "STALE_VERSION", "資料已被修改，請重新讀取再操作", request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "資料庫暫時無法使用", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "此端點不支援這個 HTTP 方法", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, request.getRequestURI(), Instant.now()));
    }
}
