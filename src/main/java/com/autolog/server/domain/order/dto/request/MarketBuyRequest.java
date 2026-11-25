package com.autolog.server.domain.order.dto.request;

import com.autolog.server.global.money.CurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 시장가 매수: KRW {@code krwAmount}만큼 {@code targetCurrency}를 산다.
 * 매도(외화 → KRW)는 PRODUCT Q7이 정해지면 추가한다.
 */
public record MarketBuyRequest(
		@NotNull CurrencyCode targetCurrency,
		@NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal krwAmount
) {
}
