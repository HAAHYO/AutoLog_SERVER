package com.autolog.server.global.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FxRoundingPolicyTest {

	@Test
	void 고객이_받는_금액은_통화_자릿수로_내림한다() {
		assertThat(FxRoundingPolicy.roundPayout(new BigDecimal("74.0749"), CurrencyCode.USD)).isEqualByComparingTo("74.07");
		assertThat(FxRoundingPolicy.roundPayout(new BigDecimal("135999.9"), CurrencyCode.KRW)).isEqualByComparingTo("135999");
	}

	@Test
	void 고객이_내는_금액은_통화_자릿수로_올림한다() {
		assertThat(FxRoundingPolicy.roundCharge(new BigDecimal("74.0701"), CurrencyCode.USD)).isEqualByComparingTo("74.08");
		assertThat(FxRoundingPolicy.roundCharge(new BigDecimal("135000.1"), CurrencyCode.KRW)).isEqualByComparingTo("135001");
	}

	@Test
	void 이미_자릿수에_맞는_금액은_그대로다() {
		assertThat(FxRoundingPolicy.roundPayout(new BigDecimal("100.00"), CurrencyCode.USD)).isEqualByComparingTo("100");
		assertThat(FxRoundingPolicy.roundCharge(new BigDecimal("135000"), CurrencyCode.KRW)).isEqualByComparingTo("135000");
	}

	@Test
	void JPY는_100엔당_고시하고_소수_자릿수는_0이다() {
		assertThat(CurrencyCode.JPY.getRateUnit()).isEqualByComparingTo("100");
		assertThat(CurrencyCode.JPY.getScale()).isZero();
		assertThat(CurrencyCode.USD.getRateUnit()).isEqualByComparingTo("1");
	}

	@Test
	void 통화_자릿수를_넘는_금액을_판별한다() {
		assertThat(CurrencyCode.KRW.fitsScale(new BigDecimal("1000.00"))).isTrue();
		assertThat(CurrencyCode.KRW.fitsScale(new BigDecimal("1000.5"))).isFalse();
		assertThat(CurrencyCode.USD.fitsScale(new BigDecimal("10.12"))).isTrue();
		assertThat(CurrencyCode.USD.fitsScale(new BigDecimal("10.123"))).isFalse();
	}
}
