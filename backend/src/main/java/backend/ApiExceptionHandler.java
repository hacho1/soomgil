package backend;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeParseException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AuthRateLimit.Exceeded.class)
    ResponseEntity<?> throttled(AuthRateLimit.Exceeded exception) {
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(exception.seconds))
                .body(Map.of("message", exception.getMessage(), "retryAfterSeconds", exception.seconds));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> responseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() { return ResponseEntity.status(409).body(Map.of("message", "이미 등록된 정보입니다.")); }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("message", "항목을 찾을 수 없습니다.")); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpMessageNotReadableException error) {
        if (RequestBodyLimit.exceeded(error)) return ResponseEntity.status(413).body(Map.of("message",RequestBodyLimit.MESSAGE));
        return invalid();
    }
    @ExceptionHandler(DateTimeParseException.class)
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message", "입력한 값의 형식을 확인해주세요.")); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(DataAccessException exception, jakarta.servlet.http.HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Database operation failed: type={} requestId={}", exception.getClass().getSimpleName(), request.getAttribute(RequestDiagnostics.ATTRIBUTE));
        return ResponseEntity.status(503).body(Map.of("message", "저장소에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }
}
