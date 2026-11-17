package com.invoiceocr.web;

import com.invoiceocr.dto.CommonDtos;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<CommonDtos.ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(CommonDtos.ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonDtos.ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                                 HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(CommonDtos.ApiError.of(400, "VALIDATION_ERROR",
                message.isBlank() ? "参数校验失败" : message, request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonDtos.ApiError> handleUploadSize(MaxUploadSizeExceededException ex,
                                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(CommonDtos.ApiError.of(413, "FILE_TOO_LARGE", "上传文件超过大小限制",
                        request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CommonDtos.ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                                HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CommonDtos.ApiError.of(409, "CONFLICT",
                        "数据冲突：该发票代码+号码已存在或字段重复", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CommonDtos.ApiError> handleAccessDenied(AccessDeniedException ex,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CommonDtos.ApiError.of(403, "FORBIDDEN", "无权执行该操作", request.getRequestURI()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<CommonDtos.ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof HttpMessageNotReadableException ? "请求体格式错误" : ex.getMessage();
        return ResponseEntity.badRequest().body(CommonDtos.ApiError.of(400, "BAD_REQUEST",
                message == null ? "请求参数错误" : message, request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CommonDtos.ApiError> handleNotFound(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(CommonDtos.ApiError.of(404, "NOT_FOUND", "资源不存在", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonDtos.ApiError> handleOther(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CommonDtos.ApiError.of(500, "INTERNAL_ERROR", "系统内部错误", request.getRequestURI()));
    }
}
