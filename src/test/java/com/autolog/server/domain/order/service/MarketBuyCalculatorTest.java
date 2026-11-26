package com.autolog.server.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.domain.rate.service.AppliedRateCalculator;
import com.autolog.server.domain.rate.service.RateQuote;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketBuyCalculatorTest {

	private final MarketBuyCalculator calculator = new MarketBuyCalculator(new AppliedRateCalculator());

	private static RateQuote quote(CurrencyCode currency, String base, String spread) {
		return new RateQuote(currency, new BigDecimal(base), new BigDecimal(spread), Instant.EPOCH, "TEST");
	}

	@Test
	void USD_매수는_외화를_내림하고_단수_차이를_스프레드_수익으로_남긴다() {
		// 적용환율 = 1350 + 1350 × 0.0175 = 1373.625
		// 외화 = 136500 / 1373.625 = 99.372... → 99.37
		// 포지션 KRW = 99.37 × 1350 = 134149.5 → 134149, 스프레드 = 136500 − 134149 = 2351
		MarketBuyCalculator.Result result = calculator.calculate(new BigDecimal("136500"),
				quote(CurrencyCode.USD, "1350", "0.0175"), BigDecimal.ZERO);

		assertThat(result.appliedRate()).isEqualByComparingTo("1373.625");
		assertThat(result.foreignAmount()).isEqualByComparingTo("99.37");
		assertThat(result.positionKrw()).isEqualByComparingTo("134149");
		assertThat(result.spreadKrw()).isEqualByComparingTo("2351");
		assertThat(result.positionKrw().add(result.spreadKrw())).isEqualByComparingTo("136500");
	}

	@Test
	void JPY는_100엔당_시세로_계산한다() {
		// 적용환율 = 905.12 × 1.0175 = 920.9596 (100엔당)
		// 외화 = 100000 × 100 / 920.9596 = 10858.2... → 10858엔
		// 포지션 KRW = 10858 × 905.12 / 100 = 98277.9296 → 98277, 스프레드 = 1723
		MarketBuyCalculator.Result result = calculator.calculate(new BigDecimal("100000"),
				quote(CurrencyCode.JPY, "905.12", "0.0175"), BigDecimal.ZERO);

		assertThat(result.foreignAmount()).isEqualByComparingTo("10858");
		assertThat(result.positionKrw()).isEqualByComparingTo("98277");
		assertThat(result.spreadKrw()).isEqualByComparingTo("1723");
	}

	@Test
	void 우대율_100퍼센트이고_나누어떨어지면_스프레드_수익은_0이다() {
		MarketBuyCalculator.Result result = calculator.calculate(new BigDecimal("135000"),
				quote(CurrencyCode.USD, "1350", "0.0175"), BigDecimal.ONE);

		assertThat(result.appliedRate()).isEqualByComparingTo("1350");
		assertThat(result.foreignAmount()).isEqualByComparingTo("100");
		assertThat(result.spreadKrw()).isEqualByComparingTo("0");
	}

	@Test
	void 받을_외화가_0이면_거부한다() {
		assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, quote(CurrencyCode.USD, "1350", "0.0175"), BigDecimal.ZERO))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.ORDER_AMOUNT_TOO_SMALL);
	}
}
