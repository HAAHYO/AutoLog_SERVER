package com.autolog.server.domain.account.entity;

/**
 * 계좌 종류. 사용자 계좌만 잔액을 캐시하고 음수를 막는다.
 * 시스템 계좌의 잔액은 원장 합계로만 관리한다(체결마다 같은 행을 갱신하는 락 경합을 피하기 위해).
 */
public enum AccountType {

	/** 사용자 다통화 지갑 */
	USER,
	/** 환전 시 회사가 통화를 주고받는 포지션 계좌 */
	SYSTEM_FX_POSITION,
	/** 스프레드와 반올림 단수 차이가 쌓이는 수익 계좌 */
	SYSTEM_SPREAD_REVENUE,
	/** 충전·출금 등 외부 자금이 드나드는 청산 계좌 */
	SYSTEM_EXTERNAL_CLEARING;

	public boolean isUser() {
		return this == USER;
	}
}
