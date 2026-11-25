package com.autolog.server.domain.ledger.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.domain.ledger.entity.LedgerEntry;
import com.autolog.server.domain.ledger.repository.LedgerEntryRepository;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원장과 잔액 캐시의 정합성을 대사(Reconciliation)한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerReconciliationService {

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	/**
	 * 사용자 계좌는 원장 합계 = accounts.balance = 마지막 balance_after 여야 일치로 본다.
	 * 시스템 계좌는 잔액을 캐시하지 않으므로 원장 합계만 보고하고, matched는 null(비교 불가)로 둔다.
	 */
	public AccountReconciliation reconcileAccount(Long accountId) {
		Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
		BigDecimal ledgerBalance = ledgerEntryRepository.sumNetByAccountId(accountId, EntryType.CREDIT);

		if (!account.getType().isUser()) {
			return new AccountReconciliation(accountId, account.getType(), null, ledgerBalance, null, null);
		}
		BigDecimal lastBalanceAfter = ledgerEntryRepository.findTopByAccountIdOrderByIdDesc(accountId)
				.map(LedgerEntry::getBalanceAfter)
				.orElse(null);
		boolean matched = account.getBalance().compareTo(ledgerBalance) == 0
				&& (lastBalanceAfter == null
						? ledgerBalance.signum() == 0
						: lastBalanceAfter.compareTo(ledgerBalance) == 0);
		return new AccountReconciliation(accountId, account.getType(), account.getBalance(), ledgerBalance,
				lastBalanceAfter, matched);
	}

	/** 통화별 전체 원장에서 Σ DEBIT = Σ CREDIT 인지 확인한다. */
	public List<CurrencyBalance> verifyCurrencyBalances() {
		Map<CurrencyCode, BigDecimal> debits = new EnumMap<>(CurrencyCode.class);
		Map<CurrencyCode, BigDecimal> credits = new EnumMap<>(CurrencyCode.class);
		for (CurrencyTotal row : ledgerEntryRepository.sumByCurrencyAndEntryType()) {
			(row.entryType() == EntryType.DEBIT ? debits : credits).put(row.currency(), row.total());
		}
		return Stream.concat(debits.keySet().stream(), credits.keySet().stream())
				.distinct()
				.sorted()
				.map(currency -> {
					BigDecimal debit = debits.getOrDefault(currency, BigDecimal.ZERO);
					BigDecimal credit = credits.getOrDefault(currency, BigDecimal.ZERO);
					return new CurrencyBalance(currency, debit, credit, debit.compareTo(credit) == 0);
				})
				.toList();
	}
}
