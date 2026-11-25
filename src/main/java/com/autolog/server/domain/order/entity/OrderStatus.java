package com.autolog.server.domain.order.entity;

/** 주문 상태. COMPLETED, FAILED는 종결 상태라 더 이상 바뀌지 않는다. 취소·만료(Q5)는 아직 없다. */
public enum OrderStatus {
	PENDING,
	COMPLETED,
	FAILED;

	public boolean isTerminal() {
		return this != PENDING;
	}
}
