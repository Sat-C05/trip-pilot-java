package com.trippilot.presentation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code, String message, String traceId) {}

    @ExceptionHandler(com.trippilot.booking.BookingProvider.Failure.class)
    ResponseEntity<Error> provider(com.trippilot.booking.BookingProvider.Failure failure) {
        return ResponseEntity.status(failure.definitive ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error(failure.code,"Provider request failed. Refresh the quote or check booking status.",UUID.randomUUID().toString()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> known(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(new Error(exception.code(), exception.getMessage(), UUID.randomUUID().toString()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "Check the request fields and format.", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("INTERNAL_ERROR", "The request could not be completed.", UUID.randomUUID().toString()));
    }
}
