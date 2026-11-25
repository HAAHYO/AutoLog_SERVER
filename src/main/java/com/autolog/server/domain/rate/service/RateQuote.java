package com.autolog.server.domain.rate.service;

import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 한 통화의 현재 시세. 기준율은 {@link CurrencyCode#getRateUnit()} 단위당 원화(예: JPY는 100엔당)다.
 *
 * @param source 시세 출처 (예: CONFIGURED, MOCK_MARKET). 체결 결과에 함께 남긴다.
 */
public record RateQuote(CurrencyCode currency, BigDecimal baseRate, BigDecimal spreadRate, Instant observedAt, String source) {
}
