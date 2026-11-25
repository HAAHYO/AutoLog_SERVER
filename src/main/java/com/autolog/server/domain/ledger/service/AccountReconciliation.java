package com.autolog.server.domain.ledger.service;

import com.autolog.server.domain.account.entity.AccountType;
import java.math.BigDecimal;

/**
 * 계좌 대사 결과.
 *
 * @param cachedBalance    accounts.balance (시스템 계좌는 캐시하지 않으므로 null)
 * @param ledgerBalance    원장 합계 Σ CREDIT − Σ DEBIT
 * @param lastBalanceAfter 마지막 엔트리의 balance_after (엔트리가 없거나 시스템 계좌면 null)
 * @param matched          사용자 계좌의 세 값 일치 여부. 시스템 계좌는 비교할 캐시가 없으므로 null(비교 불가)
 */
public record AccountReconciliation(
		Long accountId,
		AccountType type,
		BigDecimal cachedBalance,
		BigDecimal ledgerBalance,
		BigDecimal lastBalanceAfter,
		Boolean matched
) {
}
