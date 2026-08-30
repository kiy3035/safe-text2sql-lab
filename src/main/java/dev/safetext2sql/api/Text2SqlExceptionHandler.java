package dev.safetext2sql.api;

import dev.safetext2sql.workflow.QueryWorkflowException;
import dev.safetext2sql.workflow.QueryWorkflowFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 내부 오류를 HTTP 상태와 안정적인 코드로만 변환한다.
 *
 * <p>예외 메시지, 질문, 생성 SQL, PostgreSQL 오류는 응답에 포함하지 않는다. 예상하지 못한
 * 예외도 고정 코드로 축약해 프레임워크 기본 오류 페이지가 내부 정보를 노출하지 않게 한다.</p>
 */
@RestControllerAdvice
public final class Text2SqlExceptionHandler {

    @ExceptionHandler(QueryWorkflowException.class)
    ResponseEntity<Text2SqlErrorResponse> handleWorkflowFailure(QueryWorkflowException exception) {
        HttpStatus status = statusFor(exception.failure());
        return ResponseEntity.status(status)
                .body(new Text2SqlErrorResponse(exception.failure().name(), exception.attemptCount()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Text2SqlErrorResponse> handleUnreadableRequest() {
        return ResponseEntity.badRequest()
                .body(new Text2SqlErrorResponse(QueryWorkflowFailure.INVALID_QUESTION.name(), 0));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Text2SqlErrorResponse> handleUnexpectedFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Text2SqlErrorResponse("INTERNAL_ERROR", 0));
    }

    private HttpStatus statusFor(QueryWorkflowFailure failure) {
        return switch (failure) {
            case INVALID_QUESTION -> HttpStatus.BAD_REQUEST;
            case SQL_GENERATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LLM_REQUEST_REJECTED, LLM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case DB_TIMEOUT, DB_PERMISSION_DENIED, DB_UNAVAILABLE, CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
