package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.dto.response.AccountListResponse;
import com.autolog.server.domain.account.dto.response.AccountResponse;
import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

	private final AccountRepository accountRepository;
	private final UserAccountOpener userAccountOpener;

	public AccountListResponse getMyAccounts(Long userId) {
		return new AccountListResponse(accountRepository.findAllByUserIdOrderByIdAsc(userId).stream()
				.map(AccountResponse::from)
				.toList());
	}

	/**
	 * 사용자의 통화 계좌 id를 돌려주고, 없으면 개설한다.
	 *
	 * <p>트랜잭션 없이(NOT_SUPPORTED) 실행한다. 동시 개설로 유니크 위반이 나면 다시 조회하는데,
	 * 하나의 트랜잭션 안에서 다시 읽으면 MySQL 기본 격리 수준(REPEATABLE READ)의 스냅샷 때문에
	 * 다른 트랜잭션이 방금 만든 계좌가 보이지 않는다. 그래서 비즈니스 트랜잭션보다 먼저 호출해야 한다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Long getOrOpenUserAccountId(Long userId, CurrencyCode currency) {
		return accountRepository.findByUserIdAndCurrencyAndType(userId, currency, AccountType.USER)
				.map(Account::getId)
				.orElseGet(() -> openOrFind(userId, currency));
	}

	private Long openOrFind(Long userId, CurrencyCode currency) {
		try {
			return userAccountOpener.open(userId, currency);
		} catch (DataIntegrityViolationException e) {
			return accountRepository.findByUserIdAndCurrencyAndType(userId, currency, AccountType.USER)
					.map(Account::getId)
					.orElseThrow(() -> e);
		}
	}
}
