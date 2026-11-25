package com.autolog.server.domain.rate.service;

import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설정값 시세(fx.rate.*). 시세 스트리밍이 생기기 전까지 쓰는 고정 시세와 통화별 스프레드율.
 *
 * @param baseRates                통화별 매매기준율 (rateUnit 단위당 원화)
 * @param spreadRates              통화별 스프레드율 (예: 0.0175 = 1.75%)
 * @param defaultPreferentialRate  고객 등급이 없을 때 쓰는 우대율 (등급 저장 위치는 Q8 인증 때 결정)
 */
@ConfigurationProperties(prefix = "fx.rate")
public record FxRateProperties(
		Map<CurrencyCode, BigDecimal> baseRates,
		Map<CurrencyCode, BigDecimal> spreadRates,
		BigDecimal defaultPreferentialRate
) {

	public FxRateProperties {
		baseRates = baseRates == null ? Map.of() : Map.copyOf(baseRates);
		spreadRates = spreadRates == null ? Map.of() : Map.copyOf(spreadRates);
		defaultPreferentialRate = defaultPreferentialRate == null ? BigDecimal.ZERO : defaultPreferentialRate;
	}
}
