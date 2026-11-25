package com.autolog.server.domain.ledger.service;

import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;

/** 통화별 전체 원장의 차변·대변 합계. 원장 모델 B에서는 항상 같아야 한다. */
public record CurrencyBalance(CurrencyCode currency, BigDecimal debitTotal, BigDecimal creditTotal, boolean balanced) {
}
