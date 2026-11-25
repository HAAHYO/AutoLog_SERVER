package com.autolog.server.global.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 반올림 정책. 단수 차이가 항상 회사(스프레드 수익 계정) 쪽에 남도록 한다.
 *
 * <ul>
 *   <li>적용환율: 고객이 살 때 올림, 팔 때 내림 (scale 8)</li>
 *   <li>고객이 받는 금액: 내림</li>
 *   <li>고객이 내는 금액: 올림</li>
 * </ul>
 */
public final class FxRoundingPolicy {

	public static final int RATE_SCALE = 8;
	public static final int RATIO_SCALE = 6;

	private FxRoundingPolicy() {
	}

	public static BigDecimal roundRate(BigDecimal rate, FxSide side) {
		RoundingMode mode = side == FxSide.BUY ? RoundingMode.UP : RoundingMode.DOWN;
		return rate.setScale(RATE_SCALE, mode);
	}

	public static BigDecimal roundPayout(BigDecimal amount, CurrencyCode currency) {
		return amount.setScale(currency.getScale(), RoundingMode.DOWN);
	}

	public static BigDecimal roundCharge(BigDecimal amount, CurrencyCode currency) {
		return amount.setScale(currency.getScale(), RoundingMode.UP);
	}
}
