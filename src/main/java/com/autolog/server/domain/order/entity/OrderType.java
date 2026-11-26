package com.autolog.server.domain.order.entity;

public enum OrderType {
	/** 시장가: 현재 시세로 즉시 체결 */
	MARKET,
	/** 지정가: 목표환율 도달 시 체결 (feat/limit-order) */
	LIMIT
}
