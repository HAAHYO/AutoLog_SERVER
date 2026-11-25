package com.autolog.server.domain.rate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.FxSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AppliedRateCalculatorTest {

	private final AppliedRateCalculator calculator = new AppliedRateCalculator();

	@ParameterizedTest(name = "우대율 {0}: 살 때 {1}, 팔 때 {2}")
	@CsvSource({
			"0,   1373.625, 1326.375",
			"0.8, 1354.725, 1345.275",
			"1,   1350,     1350"
	})
	void 우대율에_따라_스프레드가_줄어든다(String preferential, String expectedBuy, String expectedSell) {
		BigDecimal base = new BigDecimal("1350");
		BigDecimal spread = new BigDecimal("0.0175");
		BigDecimal pref = new BigDecimal(preferential);

		assertThat(calculator.calculate(base, spread, pref, FxSide.BUY)).isEqualByComparingTo(expectedBuy);
		assertThat(calculator.calculate(base, spread, pref, FxSide.SELL)).isEqualByComparingTo(expectedSell);
	}

	@Test
	void 살_때는_올림_팔_때는_내림으로_scale_8에_맞춘다() {
		BigDecimal base = new BigDecimal("1000");
		BigDecimal spread = new BigDecimal("0.000001");
		BigDecimal pref = new BigDecimal("0.333333");
		// 우대 후 스프레드 = 1000 × 0.000001 × 0.666667 = 0.000666667

		BigDecimal buy = calculator.calculate(base, spread, pref, FxSide.BUY);
		BigDecimal sell = calculator.calculate(base, spread, pref, FxSide.SELL);

		assertThat(buy).isEqualByComparingTo("1000.00066667");
		assertThat(sell).isEqualByComparingTo("999.99933333");
		assertThat(buy.scale()).isEqualTo(8);
		assertThat(sell.scale()).isEqualTo(8);
	}

	@Test
	void 스프레드가_0이면_기준율과_같다() {
		BigDecimal base = new BigDecimal("905.12");

		assertThat(calculator.calculate(base, BigDecimal.ZERO, BigDecimal.ZERO, FxSide.BUY)).isEqualByComparingTo(base);
		assertThat(calculator.calculate(base, BigDecimal.ZERO, BigDecimal.ZERO, FxSide.SELL)).isEqualByComparingTo(base);
	}

	@ParameterizedTest(name = "기준율 {0}, 스프레드율 {1}, 우대율 {2}")
	@CsvSource({
			"0,    0.01, 0",
			"-1,   0.01, 0",
			"1350, -0.01, 0",
			"1350, 1,    0",
			"1350, 0.01, -0.1",
			"1350, 0.01, 1.1"
	})
	void 범위를_벗어난_값은_거부한다(String base, String spread, String pref) {
		assertThatThrownBy(() -> calculator.calculate(new BigDecimal(base), new BigDecimal(spread), new BigDecimal(pref), FxSide.BUY))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_RATE_PARAMETER);
	}

	@Test
	void 빈_값은_거부한다() {
		assertThatThrownBy(() -> calculator.calculate(null, BigDecimal.ZERO, BigDecimal.ZERO, FxSide.BUY))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.INVALID_RATE_PARAMETER);
		assertThatThrownBy(() -> calculator.calculate(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null))
				.isInstanceOf(BusinessException.class);
	}
}
