package com.autolog.server.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT, e.getBindingResult()));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
				.body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
		return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
				.body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
	}
}
