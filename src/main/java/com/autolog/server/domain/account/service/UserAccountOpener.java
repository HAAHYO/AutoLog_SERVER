package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 계좌를 별도 트랜잭션에서 개설한다.
 *
 * <p>같은 사용자·통화 계좌를 두 요청이 동시에 만들면 늦은 쪽이 유니크 제약 위반으로 실패한다.
 * 그 예외가 호출한 쪽의 트랜잭션을 망가뜨리지 않도록 REQUIRES_NEW로 분리했다.
 * 호출한 쪽은 실패하면 다시 조회하면 된다({@link AccountService#getOrOpenUserAccountId}).
 */
@Component
@RequiredArgsConstructor
public class UserAccountOpener {

	private final AccountRepository accountRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long open(Long userId, CurrencyCode currency) {
		return accountRepository.saveAndFlush(Account.openUserAccount(userId, currency)).getId();
	}
}
