package com.skillforge.studio.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
/**
 * 将业务异常和参数校验异常转换为稳定 JSON，前端无需解析 Spring 默认错误页面。
 */
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
            "code", exception.getStatusCode().toString(),
            "message", exception.getReason() == null ? "请求处理失败" : exception.getReason(),
            "path", request.getRequestURI()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
            ? "请求参数不正确"
            : exception.getBindingResult().getFieldErrors().getFirst().getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of(
            "code", "VALIDATION_FAILED",
            "message", message,
            "path", request.getRequestURI()
        ));
    }

    /** 数据库唯一索引负责并发场景下的最终兜底，统一转换成可理解的 409 响应。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "数据已存在，请勿重复提交", request);
    }

    /** 单个文件或整次请求超过配置容量时使用 413，前端可以据此保留已选择的文件。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadLimit(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传内容超过大小限制", request);
    }

    /** multipart 结构损坏或 part 数量超限时不再返回不透明的 500。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "上传内容格式不正确或文件数量过多", request);
    }

    /** 处理控制器方法参数上的 Bean Validation 约束。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        String message = exception.getConstraintViolations().stream()
            .findFirst()
            .map(violation -> violation.getMessage())
            .orElse("请求参数不正确");
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
            "code", status.toString(),
            "message", message,
            "path", request.getRequestURI()
        ));
    }
}
