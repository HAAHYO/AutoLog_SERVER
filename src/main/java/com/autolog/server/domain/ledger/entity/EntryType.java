package com.autolog.server.domain.ledger.entity;

/** 은행 관점의 차변·대변. 모든 계좌에서 DEBIT = 잔액 감소(출금), CREDIT = 잔액 증가(입금). */
public enum EntryType {
	DEBIT,
	CREDIT
}
