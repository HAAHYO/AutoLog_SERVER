package com.autolog.server.domain.ledger.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.domain.ledger.entity.LedgerEntry;
import com.autolog.server.domain.ledger.entity.LedgerTxGroup;
import com.autolog.server.domain.ledger.repository.LedgerEntryRepository;
import com.autolog.server.domain.ledger.repository.LedgerTxGroupRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerService {

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final LedgerTxGroupRepository ledgerTxGroupRepository;
	private final Clock clock;

	/**
	 * 검증된 거래 묶음을 원장에 기록하고, 같은 트랜잭션에서 사용자 계좌 잔액을 갱신한다.
	 *
	 * <p>처리 순서와 이유
	 * <ol>
	 *   <li>거래 묶음 헤더(ledger_tx_groups, 기본키 = txGroupId)를 먼저 INSERT한다.
	 *       같은 txGroupId가 동시에 들어오면 늦은 쪽이 기본키 위반으로 실패하므로 중복 기록이 DB에서 막힌다.</li>
	 *   <li>계좌 잔액을 바꾸고 <b>엔트리보다 먼저 flush</b>한다. 엔트리 INSERT는 외래키 때문에 부모 계좌 행에
	 *       공유 락을 거는데, 그 뒤에 같은 행을 UPDATE하려 하면 동시 트랜잭션끼리 MySQL 데드락이 난다.
	 *       UPDATE(배타 락)를 먼저 하면 두 번째 트랜잭션은 기다렸다가 {@code @Version} 충돌로 실패한다.</li>
	 *   <li>엔트리를 INSERT한다.</li>
	 * </ol>
	 */
	@Transactional
	public String post(TxGroup txGroup) {
		Instant now = Instant.now(clock);
		openTxGroup(txGroup.txGroupId(), now);
		Map<Long, Account> accounts = loadAccounts(txGroup);

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
		accountRepository.flush();
		ledgerEntryRepository.saveAll(entries);
		return txGroup.txGroupId();
	}

	private void openTxGroup(String txGroupId, Instant now) {
		if (ledgerTxGroupRepository.existsById(txGroupId)) {
			throw new BusinessException(ErrorCode.LEDGER_DUPLICATE_TX_GROUP);
		}
		try {
			ledgerTxGroupRepository.saveAndFlush(LedgerTxGroup.open(txGroupId, now));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.LEDGER_DUPLICATE_TX_GROUP);
		}
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
