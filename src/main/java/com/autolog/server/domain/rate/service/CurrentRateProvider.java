package com.autolog.server.domain.rate.service;

import com.autolog.server.global.money.CurrencyCode;

/**
 * 현재 시세 조회. 지금은 설정값 구현({@link ConfiguredRateProvider})만 있고,
 * 시세 스트리밍(feat/rate-streaming)에서 Redis 최신 시세 구현으로 바꾼다.
 */
public interface CurrentRateProvider {

	/** @throws com.autolog.server.global.exception.BusinessException 시세가 없으면 RATE_UNAVAILABLE */
	RateQuote getQuote(CurrencyCode currency);
}
