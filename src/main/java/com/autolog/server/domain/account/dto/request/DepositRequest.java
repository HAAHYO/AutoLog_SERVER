package com.autolog.server.domain.account.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 모의 충전 (KRW만, 원 단위 정수). */
public record DepositRequest(
		@NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal amount
) {
}
