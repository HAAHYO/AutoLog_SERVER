package com.autolog.server.global.money;

import java.math.BigDecimal;
import lombok.Getter;

/**
 * 지원 통화 (ISO 4217).
 *
 * <p>{@code scale}은 고객에게 지급·차감되는 최종 금액의 소수 자릿수이고,
 * {@code rateUnit}은 환율 고시 기준 단위다. JPY는 국내 관례대로 100엔당 원화로 고시한다.
 */
@Getter
public enum CurrencyCode {

	KRW(0, 1),
	USD(2, 1),
	JPY(0, 100),
	EUR(2, 1);

	private final int scale;
	private final BigDecimal rateUnit;

	CurrencyCode(int scale, int rateUnit) {
		this.scale = scale;
		this.rateUnit = BigDecimal.valueOf(rateUnit);
	}

	/** 금액의 소수 자릿수가 이 통화의 최종 금액 자릿수를 넘지 않는지 확인한다. */
	public boolean fitsScale(BigDecimal amount) {
		return amount.stripTrailingZeros().scale() <= scale;
	}
}
