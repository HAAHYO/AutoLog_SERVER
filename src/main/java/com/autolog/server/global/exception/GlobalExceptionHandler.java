package com.autolog.server.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
		if (errorCode.getStatus().is5xxServerError()) {
			// 5xx는 내부 불변식 위반(버그)이므로 상세 메시지는 로그에만 남기고 응답에는 기본 메시지만 준다.
			log.error("Business invariant violated: {} - {}", errorCode.getCode(), e.getMessage(), e);
			return ResponseEntity.status(errorCode.getStatus())
					.body(ErrorResponse.of(errorCode));
		}
		return ResponseEntity.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, e.getMessage()));
	}

	/**
	 * 같은 계좌를 동시에 갱신해 낙관적 락(@Version) 충돌이나 락 대기 실패·데드락이 난 경우.
	 * 비즈니스 실패가 아니라 재시도하면 성공할 수 있는 실패이므로 409로 응답한다.
	 */
	@ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
	public ResponseEntity<ErrorResponse> handleConcurrentUpdate(RuntimeException e) {
		log.warn("Concurrent update conflict: {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.CONCURRENT_UPDATE.getStatus())
				.body(ErrorResponse.of(ErrorCode.CONCURRENT_UPDATE));
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
