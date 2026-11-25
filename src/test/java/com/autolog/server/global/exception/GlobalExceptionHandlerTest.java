package com.autolog.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void 낙관적_락_충돌은_409로_응답한다() {
		ResponseEntity<ErrorResponse> response = handler.handleConcurrentUpdate(
				new ObjectOptimisticLockingFailureException("Account", 1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().code()).isEqualTo("C004");
	}

	@Test
	void 락_획득_실패도_409로_응답한다() {
		ResponseEntity<ErrorResponse> response = handler.handleConcurrentUpdate(
				new CannotAcquireLockException("Deadlock found"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void 서버_오류_비즈니스_예외는_상세_메시지를_응답에_노출하지_않는다() {
		ResponseEntity<ErrorResponse> response = handler.handleBusinessException(
				new BusinessException(ErrorCode.LEDGER_UNBALANCED, "currency=KRW, CREDIT-DEBIT=1"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().message()).isEqualTo(ErrorCode.LEDGER_UNBALANCED.getMessage());
	}

	@Test
	void 클라이언트_오류_비즈니스_예외는_상세_메시지를_그대로_준다() {
		ResponseEntity<ErrorResponse> response = handler.handleBusinessException(
				new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "우대율은 0 이상 1 이하여야 합니다."));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message()).isEqualTo("우대율은 0 이상 1 이하여야 합니다.");
	}
}
