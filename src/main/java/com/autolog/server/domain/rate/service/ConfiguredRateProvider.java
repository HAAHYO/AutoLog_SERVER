package com.autolog.server.domain.rate.service;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 설정 파일의 고정 시세를 돌려준다. 관측 시각은 조회 시각으로 둔다. */
@Component
@RequiredArgsConstructor
public class ConfiguredRateProvider implements CurrentRateProvider {

	public static final String SOURCE = "CONFIGURED";

	private final FxRateProperties properties;
	private final Clock clock;

	@Override
	public RateQuote getQuote(CurrencyCode currency) {
		BigDecimal baseRate = properties.baseRates().get(currency);
		BigDecimal spreadRate = properties.spreadRates().get(currency);
		if (baseRate == null || spreadRate == null) {
			throw new BusinessException(ErrorCode.RATE_UNAVAILABLE, "설정된 시세가 없습니다: " + currency);
		}
		return new RateQuote(currency, baseRate, spreadRate, Instant.now(clock), SOURCE);
	}
}
