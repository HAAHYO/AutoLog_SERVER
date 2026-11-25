package com.autolog.server.domain.ledger.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.domain.ledger.entity.LedgerEntry;
import com.autolog.server.domain.ledger.repository.LedgerEntryRepository;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerService {

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final Clock clock;

	/**
	 * 검증된 거래 묶음을 원장에 기록하고, 같은 트랜잭션에서 사용자 계좌 잔액을 갱신한다.
	 *
	 * <p>잔액 갱신은 {@code Account}의 {@code @Version}으로 보호된다. 같은 계좌를 동시에 갱신하면
	 * 늦은 쪽이 커밋할 때 낙관적 락 예외로 실패하고, 엔트리까지 트랜잭션 전체가 롤백된다.
	 *
	 * <p>{@code existsByTxGroupId} 확인은 같은 거래의 순차 재시도만 막고, 동시 요청은 막지 못한다.
	 * 정확히 한 번은 호출부의 자연키 UNIQUE 제약(주문, 멱등성 키)으로 보장한다.
	 */
	@Transactional
	public String post(TxGroup txGroup) {
		if (ledgerEntryRepository.existsByTxGroupId(txGroup.txGroupId())) {
			throw new BusinessException(ErrorCode.LEDGER_DUPLICATE_TX_GROUP);
		}
		Map<Long, Account> accounts = loadAccounts(txGroup);
		Instant now = Instant.now(clock);

		List<LedgerEntry> entries = new ArrayList<>();
		for (TxGroup.Line line : txGroup.lines()) {
			Account account = accounts.get(line.accountId());
			if (account.getCurrency() != line.currency()) {
				throw new BusinessException(ErrorCode.LEDGER_INVALID_ENTRY,
						"계좌 통화와 라인 통화가 다릅니다: accountId=" + account.getId() + ", line=" + line);
			}
			BigDecimal balanceAfter = line.entryType() == EntryType.DEBIT
					? account.debit(line.amount())
					: account.credit(line.amount());
			entries.add(LedgerEntry.record(txGroup.txGroupId(), account, line.entryType(), line.amount(),
					balanceAfter, line.appliedRate(), now));
		}
		ledgerEntryRepository.saveAll(entries);
		return txGroup.txGroupId();
	}

	private Map<Long, Account> loadAccounts(TxGroup txGroup) {
		List<Long> ids = txGroup.lines().stream()
				.map(TxGroup.Line::accountId)
				.distinct()
				.sorted()
				.toList();
		List<Account> found = accountRepository.findAllByIdInOrderByIdAsc(ids);
		if (found.size() != ids.size()) {
			throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
		}
		return found.stream().collect(Collectors.toMap(Account::getId, Function.identity()));
	}
}
