package lab.helpdesk.ticket.web;

import lab.helpdesk.ticket.application.TicketNotFoundException;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class TicketApiExceptionHandler
        extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String detail = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getDefaultMessage())
                .filter(message -> message != null)
                .findFirst()
                .orElse("request validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                detail);

        problem.setTitle("Bad Request");

        return handleExceptionInternal(
                exception,
                problem,
                headers,
                status,
                request);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail handleTicketNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "ticket not found");

        problem.setTitle("Not Found");

        return problem;
    }

    // id가 아닌 다른 입력 변환 오류까지 "Ticket ID 오류"라고 잘못 표현하지 않도록 일반 메시지를 기본값으로 둔다.
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String detail = "request value has invalid format";

        if (exception instanceof MethodArgumentTypeMismatchException mismatch
                && "id".equals(mismatch.getName())) {

            detail = "ticket id must be a number";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                detail);

        problem.setTitle("Bad Request");

        return handleExceptionInternal(
                exception,
                problem,
                headers,
                status,
                request);
    }

    // JSON 자체를 읽지 못하는 경우를 별도 처리하여, Validation 실패와 JSON 문법 실패를 구분.
    // 원래 Exception 메시지를 사용하지 않으므로 Jackson 내부 파싱 정보나 Java 클래스 정보가 Client에게 노출되지 않는다.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "request body is malformed");

        problem.setTitle("Bad Request");

        return handleExceptionInternal(
                exception,
                problem,
                headers,
                status,
                request);
    }

    // 전역 500 Handler 추가: 예상하지 못한 내부 실패를 안전한 500 ProblemDetail로 변환
    // Client: 안전한 일반 메시지만 수신, Server Log: 원래 Exception과 Stack Trace 보존, Error까지 무조건 잡지 않고 일반적인 Exception만 처리
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(
            Exception exception) {

        logger.error(
                "unexpected server error",
                exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "an unexpected server error occurred");

        problem.setTitle("Internal Server Error");

        return problem;
    }
}
