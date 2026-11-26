package com.autolog.server.domain.account.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 모의 충전 (KRW만, 원 단위 정수). */
public record DepositRequest(
		@NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal amount
) {

	/** 같은 값을 다른 표기(1000, 1000.00)로 보내도 멱등성 해시가 같도록 정규화한다. */
	public DepositRequest {
		amount = amount == null ? null : amount.stripTrailingZeros();
	}
}
